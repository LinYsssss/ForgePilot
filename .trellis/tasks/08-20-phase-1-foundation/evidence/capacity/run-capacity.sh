#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../../../../" && pwd)"
evidence_root="$repo_root/.trellis/tasks/08-20-phase-1-foundation/evidence/capacity"
run_id="${1:-$(date -u +%Y%m%dT%H%M%SZ)-$(git -C "$repo_root" rev-parse --short=12 HEAD)}"
project="forgepilot-phase1-cap-${run_id,,}"
run_dir="$evidence_root/$run_id"
compose_file="$repo_root/compose.yaml"
backend_port="${FORGEPILOT_BACKEND_PORT:-18080}"
frontend_port="${FORGEPILOT_FRONTEND_PORT:-18081}"
backend_url="http://127.0.0.1:$backend_port"
frontend_url="http://127.0.0.1:$frontend_port"
baseline_samples=21
steady_samples=17
sample_interval=15
warmup_seconds=120
required_steady_seconds=240
events_pid=""
stack_started=0
finalized=0
export FORGEPILOT_SPRING_PROFILES_ACTIVE=capacity

monotonic_ms() {
  awk '{printf "%.0f\n", $1 * 1000}' /proc/uptime
}

sleep_until_sample() {
  local phase_start_ms=$1
  local sample=$2
  local target_ms now_ms remaining_ms
  target_ms=$((phase_start_ms + (sample - 1) * sample_interval * 1000))
  now_ms="$(monotonic_ms)"
  if ((now_ms < target_ms)); then
    remaining_ms=$((target_ms - now_ms))
    sleep "$(printf '%d.%03d' $((remaining_ms / 1000)) $((remaining_ms % 1000)))"
  fi
}

normalize_log_files() {
  find "$run_dir" -maxdepth 1 -type f -name '*.log' \
    -exec sed -i -E 's/[[:blank:]]+$//' {} +
}

if [[ ! "$run_id" =~ ^[0-9]{8}T[0-9]{6}Z-[a-z0-9][a-z0-9._-]*$ ]]; then
  printf 'Invalid run id: %s\n' "$run_id" >&2
  exit 2
fi
if [[ ! "$project" =~ ^forgepilot-phase1-cap-[a-z0-9][a-z0-9._-]*$ ]]; then
  printf 'Unsafe Compose project name: %s\n' "$project" >&2
  exit 2
fi
if [[ -e "$run_dir" ]]; then
  printf 'Refusing to overwrite capacity evidence: %s\n' "$run_dir" >&2
  exit 2
fi

mkdir -p "$run_dir"
cd "$repo_root"
compose=(docker compose --file "$compose_file" --project-name "$project")

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$events_pid" ]]; then
    kill "$events_pid" 2>/dev/null || true
    wait "$events_pid" 2>/dev/null || true
  fi
  if ((finalized == 0)); then
    printf 'INVALID\texit=%s\ttime=%s\n' "$status" "$(date -u +%FT%TZ)" >"$run_dir/run-status.txt"
  fi
  if ((stack_started == 1)); then
    if [[ "$project" == forgepilot-phase1-cap-* ]]; then
      "${compose[@]}" down --volumes --remove-orphans >>"$run_dir/commands.log" 2>&1 || true
    fi
  fi
  normalize_log_files
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

log_command() {
  printf '%s\t%s\n' "$(date -u +%FT%TZ)" "$*" >>"$run_dir/commands.log"
}

require_existing_services() {
  local name
  for name in cpa-manager-plus cli-proxy-api; do
    [[ "$(docker inspect --format '{{.State.Running}}' "$name" 2>/dev/null)" == "true" ]] || {
      printf 'Required existing container is not running: %s\n' "$name" >&2
      exit 2
    }
  done
  [[ "$(systemctl is-active cloudflared)" == "active" ]] || {
    printf 'Required existing service is not active: cloudflared\n' >&2
    exit 2
  }
}

container_name_for_service() {
  local service=$1
  local id
  id="$("${compose[@]}" ps --quiet "$service")"
  docker inspect --format '{{.Name}}' "$id" | sed 's#^/##'
}

append_host_sample() {
  local phase=$1
  local sample=$2
  local phase_start_ms=$3
  local now_ms elapsed
  now_ms="$(monotonic_ms)"
  elapsed=$(((now_ms - phase_start_ms) / 1000))
  local pswpin pswpout
  pswpin="$(awk '$1 == "pswpin" {print $2}' /proc/vmstat)"
  pswpout="$(awk '$1 == "pswpout" {print $2}' /proc/vmstat)"
  awk -v ts="$(date -u +%FT%TZ)" -v phase="$phase" -v sample="$sample" -v elapsed="$elapsed" -v pswpin="$pswpin" -v pswpout="$pswpout" '
    BEGIN { OFS="\t" }
    /^(MemTotal|MemAvailable|MemFree|Buffers|Cached|SReclaimable|SwapTotal|SwapFree):/ {
      value[$1]=$2
    }
    END {
      print ts, phase, sample, elapsed,
        value["MemTotal:"], value["MemAvailable:"], value["MemFree:"],
        value["Buffers:"], value["Cached:"], value["SReclaimable:"],
        value["SwapTotal:"], value["SwapFree:"], pswpin, pswpout
    }
  ' /proc/meminfo >>"$run_dir/host-memory.tsv"
  awk -v ts="$(date -u +%FT%TZ)" -v phase="$phase" -v sample="$sample" -v elapsed="$elapsed" '
    BEGIN { OFS="\t" }
    /^some / { some10=$2; some60=$3; some300=$4; sometotal=$5 }
    /^full / { full10=$2; full60=$3; full300=$4; fulltotal=$5 }
    END { print ts, phase, sample, elapsed, some10, some60, some300, sometotal, full10, full60, full300, fulltotal }
  ' /proc/pressure/memory >>"$run_dir/memory-pressure.tsv"
}

append_docker_stats() {
  local phase=$1
  local sample=$2
  local timestamp
  timestamp="$(date -u +%FT%TZ)"
  while IFS= read -r line; do
    jq -cn --arg ts "$timestamp" --arg phase "$phase" --argjson sample "$sample" --argjson stat "$line" \
      '{timestamp:$ts,phase:$phase,sample:$sample,stat:$stat}' >>"$run_dir/docker-stats.jsonl"
  done < <(docker stats --no-stream --format '{{json .}}')
}

append_process_sample() {
  local phase=$1
  local sample=$2
  local timestamp service container pid values
  timestamp="$(date -u +%FT%TZ)"
  for container in $(docker ps --format '{{.Names}}'); do
    case "$container" in
      cpa-manager-plus|cli-proxy-api|"$project"-*) ;;
      *) continue ;;
    esac
    service="$container"
    while IFS= read -r pid; do
      [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/smaps_rollup" ]] || continue
      if ! values="$(awk '
        /^Rss:/ { rss=$2 }
        /^Pss:/ { pss=$2 }
        /^Pss_Anon:/ { anon=$2 }
        /^Pss_File:/ { file=$2 }
        /^Swap:/ { swap=$2 }
        END { printf "%s\t%s\t%s\t%s\t%s", rss+0, pss+0, anon+0, file+0, swap+0 }
      ' "/proc/$pid/smaps_rollup" 2>/dev/null)"; then
        continue
      fi
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$timestamp" "$phase" "$sample" "$service" "$pid" "$values" >>"$run_dir/process-smaps.tsv"
    done < <(docker top "$container" -eo pid 2>/dev/null | awk 'NR > 1 {print $1}')
  done

  pid="$(systemctl show cloudflared -p MainPID --value)"
  if [[ "$pid" =~ ^[0-9]+$ && "$pid" != "0" && -r "/proc/$pid/smaps_rollup" ]]; then
    if ! values="$(awk '
      /^Rss:/ { rss=$2 }
      /^Pss:/ { pss=$2 }
      /^Pss_Anon:/ { anon=$2 }
      /^Pss_File:/ { file=$2 }
      /^Swap:/ { swap=$2 }
      END { printf "%s\t%s\t%s\t%s\t%s", rss+0, pss+0, anon+0, file+0, swap+0 }
    ' "/proc/$pid/smaps_rollup" 2>/dev/null)"; then
      return 0
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$timestamp" "$phase" "$sample" cloudflared "$pid" "$values" >>"$run_dir/process-smaps.tsv"
  fi
}

metric_json() {
  local name=$1
  local tag=${2:-}
  local url="$backend_url/actuator/metrics/$name"
  if [[ -n "$tag" ]]; then
    url="$url?tag=$tag"
  fi
  curl --fail --silent --show-error --max-time 10 "$url"
}

append_jvm_sample() {
  local sample=$1
  local timestamp heap_used heap_committed heap_max nonheap_used nonheap_committed nonheap_max direct_used direct_count metaspace_used metaspace_max
  timestamp="$(date -u +%FT%TZ)"
  heap_used="$(metric_json jvm.memory.used area:heap)"
  heap_committed="$(metric_json jvm.memory.committed area:heap)"
  heap_max="$(metric_json jvm.memory.max area:heap)"
  nonheap_used="$(metric_json jvm.memory.used area:nonheap)"
  nonheap_committed="$(metric_json jvm.memory.committed area:nonheap)"
  nonheap_max="$(metric_json jvm.memory.max area:nonheap)"
  direct_used="$(metric_json jvm.buffer.memory.used id:direct)"
  direct_count="$(metric_json jvm.buffer.count id:direct)"
  metaspace_used="$(metric_json jvm.memory.used id:Metaspace || printf 'null')"
  metaspace_max="$(metric_json jvm.memory.max id:Metaspace || printf 'null')"
  jq -cn \
    --arg ts "$timestamp" \
    --argjson sample "$sample" \
    --argjson heapUsed "$heap_used" \
    --argjson heapCommitted "$heap_committed" \
    --argjson heapMax "$heap_max" \
    --argjson nonHeapUsed "$nonheap_used" \
    --argjson nonHeapCommitted "$nonheap_committed" \
    --argjson nonHeapMax "$nonheap_max" \
    --argjson directUsed "$direct_used" \
    --argjson directCount "$direct_count" \
    --argjson metaspaceUsed "$metaspace_used" \
    --argjson metaspaceMax "$metaspace_max" \
    '{timestamp:$ts,sample:$sample,heapUsed:$heapUsed,heapCommitted:$heapCommitted,heapMax:$heapMax,nonHeapUsed:$nonHeapUsed,nonHeapCommitted:$nonHeapCommitted,nonHeapMax:$nonHeapMax,directUsed:$directUsed,directCount:$directCount,metaspaceUsed:$metaspaceUsed,metaspaceMax:$metaspaceMax}' \
    >>"$run_dir/jvm-memory.jsonl"
}

append_health_sample() {
  local sample=$1
  local backend_code frontend_code proxy_code pg_code backend_body
  backend_body="$(mktemp)"
  backend_code="$(curl --silent --show-error --max-time 10 --output "$backend_body" --write-out '%{http_code}' "$backend_url/actuator/health" || true)"
  frontend_code="$(curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}' "$frontend_url/" || true)"
  proxy_code="$(curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}' "$frontend_url/api/actuator/health" || true)"
  if "${compose[@]}" exec --no-TTY postgres sh -ec 'pg_isready --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"' >/dev/null 2>&1; then
    pg_code=0
  else
    pg_code=1
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(date -u +%FT%TZ)" "$sample" "$backend_code" "$frontend_code" "$proxy_code" "$pg_code" \
    "$(tr -d '\n\r\t' <"$backend_body")" >>"$run_dir/health.tsv"
  rm -f -- "$backend_body"
}

query_postgres() {
  local sql=$1
  "${compose[@]}" exec --no-TTY postgres sh -ec \
    'psql -X --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --csv --command "$1"' \
    capacity "$sql"
}

write_postgres_settings() {
  local target=$1
  query_postgres "
    select current_setting('server_version_num') as server_version_num;
    select extversion from pg_extension where extname = 'vector';
    select name, setting, unit, source
    from pg_settings
    where name in (
      'shared_buffers', 'work_mem', 'maintenance_work_mem', 'autovacuum_work_mem',
      'temp_buffers', 'max_connections', 'wal_buffers', 'effective_cache_size',
      'max_worker_processes', 'max_parallel_workers', 'max_parallel_workers_per_gather',
      'huge_pages', 'jit'
    )
    order by name;
  " >"$target"
}

write_safe_container_inspect() {
  local target=$1
  shift
  docker inspect "$@" | jq '[.[] | {
    Id,
    Name,
    Created,
    Image,
    Path,
    State: {
      Status: .State.Status,
      Running: .State.Running,
      OOMKilled: .State.OOMKilled,
      StartedAt: .State.StartedAt,
      FinishedAt: .State.FinishedAt,
      Health: .State.Health
    },
    RestartCount,
    HostConfig: {
      Memory: .HostConfig.Memory,
      MemorySwap: .HostConfig.MemorySwap,
      NanoCpus: .HostConfig.NanoCpus,
      PidsLimit: .HostConfig.PidsLimit
    },
    Config: {
      Image: .Config.Image,
      Healthcheck: .Config.Healthcheck,
      Labels: .Config.Labels
    }
  }]' >"$target"
}

printf 'timestamp\tphase\tsample\telapsed_seconds\tMemTotal_kB\tMemAvailable_kB\tMemFree_kB\tBuffers_kB\tCached_kB\tSReclaimable_kB\tSwapTotal_kB\tSwapFree_kB\tpswpin\tpswpout\n' >"$run_dir/host-memory.tsv"
printf 'timestamp\tphase\tsample\telapsed_seconds\tsome_avg10\tsome_avg60\tsome_avg300\tsome_total\tfull_avg10\tfull_avg60\tfull_avg300\tfull_total\n' >"$run_dir/memory-pressure.tsv"
printf 'timestamp\tphase\tsample\tservice\tpid\tRss_kB\tPss_kB\tPss_Anon_kB\tPss_File_kB\tSwap_kB\n' >"$run_dir/process-smaps.tsv"
printf 'timestamp\tsample\tbackend_http\tfrontend_http\tproxy_http\tpostgres_exit\tbackend_body\n' >"$run_dir/health.tsv"

require_existing_services
if [[ -n "$(docker ps --all --quiet --filter "label=com.docker.compose.project=$project")" || \
      -n "$(docker volume ls --quiet --filter "label=com.docker.compose.project=$project")" || \
      -n "$(docker network ls --quiet --filter "label=com.docker.compose.project=$project")" ]]; then
  printf 'Capacity project already has Docker resources: %s\n' "$project" >&2
  exit 2
fi

{
  printf 'run_id=%s\nproject=%s\nstart_utc=%s\n' "$run_id" "$project" "$(date -u +%FT%TZ)"
  uname -a
  printf 'cpu_count=%s\n' "$(nproc)"
  free -b
  swapon --show --bytes
  docker version
  docker compose version
  printf '\nExisting containers:\n'
  docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
  printf '\ncloudflared:\n'
  systemctl show cloudflared -p ActiveState -p SubState -p MainPID -p MemoryCurrent -p MemoryPeak -p MemoryMax -p NRestarts
  printf '\nPotential one-shot build/test processes before baseline:\n'
  pgrep -af 'mvn|npm|vite|docker build' || true
} >"$run_dir/host.txt" 2>&1
git rev-parse HEAD >"$run_dir/git-head.txt"
git status --short >"$run_dir/git-status.txt"
sha256sum "$compose_file" >"$run_dir/compose.sha256"
"${compose[@]}" config --no-interpolate >"$run_dir/compose.rendered.yaml"
"${compose[@]}" config --no-interpolate --format json >"$run_dir/compose.rendered.json"
if ! journalctl -k --utc --since '7 days ago' --no-pager >"$run_dir/kernel-before.log" 2>"$run_dir/kernel-before.err"; then
  printf 'KERNEL_JOURNAL_UNAVAILABLE\n' >>"$run_dir/kernel-before.log"
  printf 'journalctl failed for the baseline window; see kernel-before.err\n' >&2
fi
write_safe_container_inspect "$run_dir/existing-containers-before.json" cpa-manager-plus cli-proxy-api
systemctl show cloudflared -p ActiveState -p SubState -p MainPID -p MemoryCurrent -p MemoryPeak -p MemoryMax -p NRestarts >"$run_dir/cloudflared-before.txt"

log_command "docker compose --project-name $project build"
"${compose[@]}" build >"$run_dir/build.log" 2>&1

printf 'Baseline: %s samples at %ss intervals.\n' "$baseline_samples" "$sample_interval"
baseline_start_ms="$(monotonic_ms)"
for ((sample=1; sample<=baseline_samples; sample++)); do
  sleep_until_sample "$baseline_start_ms" "$sample"
  append_host_sample baseline "$sample" "$baseline_start_ms"
  append_docker_stats baseline "$sample"
  append_process_sample baseline "$sample"
done

log_command "docker compose --project-name $project up --detach --no-build --wait"
stack_started=1
"${compose[@]}" up --detach --no-build --wait --wait-timeout 240 >"$run_dir/compose-up.log" 2>&1
"${compose[@]}" ps >"$run_dir/compose-ps-start.txt"
"${compose[@]}" ps --format json >"$run_dir/compose-ps-start.json"
"${compose[@]}" images --format json >"$run_dir/images.json"
write_safe_container_inspect "$run_dir/forgepilot-containers-start.json" \
  "$("${compose[@]}" ps --quiet postgres)" \
  "$("${compose[@]}" ps --quiet backend)" \
  "$("${compose[@]}" ps --quiet frontend)"

backend_name="$(container_name_for_service backend)"
frontend_name="$(container_name_for_service frontend)"
postgres_name="$(container_name_for_service postgres)"
docker events --since "$(date -u +%FT%TZ)" \
  --filter type=container \
  --filter "label=com.docker.compose.project=$project" \
  --format '{{json .}}' >"$run_dir/docker-events.jsonl" 2>&1 &
events_pid=$!

append_health_sample 0
query_postgres "select current_setting('server_version_num')::integer >= 150000 as postgres_15_or_newer;" >"$run_dir/postgres-version.csv"
query_postgres "select extversion from pg_extension where extname = 'vector'; select '[1,2,3]'::vector <-> '[1,2,4]'::vector as distance;" >"$run_dir/pgvector.csv"
query_postgres "select version, description, success from flyway_schema_history order by installed_rank;" >"$run_dir/flyway.csv"
query_postgres "select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name <> 'flyway_schema_history' order by table_name;" >"$run_dir/application-tables.csv"
write_postgres_settings "$run_dir/postgres-settings-start.csv"

printf 'Warm-up: %ss with 5s health requests.\n' "$warmup_seconds"
warmup_start_ms="$(monotonic_ms)"
warmup_iteration=0
while (( ($(monotonic_ms) - warmup_start_ms) < warmup_seconds * 1000 )); do
  warmup_iteration=$((warmup_iteration + 1))
  curl --fail --silent --show-error --max-time 10 "$backend_url/actuator/health" >/dev/null
  curl --fail --silent --show-error --max-time 10 "$frontend_url/" >/dev/null
  sleep 5
done

steady_start_utc="$(date -u +%FT%TZ)"
steady_start_journal="$(date +'%F %T')"
steady_start_ms="$(monotonic_ms)"
printf '%s\n' "$steady_start_utc" >"$run_dir/steady-start.txt"
printf 'Steady window: %s samples at %ss intervals.\n' "$steady_samples" "$sample_interval"
for ((sample=1; sample<=steady_samples; sample++)); do
  sleep_until_sample "$steady_start_ms" "$sample"
  append_host_sample steady "$sample" "$steady_start_ms"
  append_docker_stats steady "$sample"
  append_process_sample steady "$sample"
  append_jvm_sample "$sample"
  if ((sample == 1 || sample % 2 == 0 || sample == steady_samples)); then
    append_health_sample "$sample"
  fi
done
steady_end_utc="$(date -u +%FT%TZ)"
steady_end_ms="$(monotonic_ms)"
steady_elapsed=$(((steady_end_ms - steady_start_ms) / 1000))
printf '%s\n' "$steady_end_utc" >"$run_dir/steady-end.txt"

write_postgres_settings "$run_dir/postgres-settings-end.csv"
"${compose[@]}" ps >"$run_dir/compose-ps-end.txt"
"${compose[@]}" ps --format json >"$run_dir/compose-ps-end.json"
"${compose[@]}" logs --no-color --timestamps 2>&1 \
  | sed -E 's/[[:blank:]]+$//' >"$run_dir/service-logs.txt"
write_safe_container_inspect "$run_dir/forgepilot-containers-end.json" \
  "$("${compose[@]}" ps --quiet postgres)" \
  "$("${compose[@]}" ps --quiet backend)" \
  "$("${compose[@]}" ps --quiet frontend)"
write_safe_container_inspect "$run_dir/existing-containers-after.json" cpa-manager-plus cli-proxy-api
systemctl show cloudflared -p ActiveState -p SubState -p MainPID -p MemoryCurrent -p MemoryPeak -p MemoryMax -p NRestarts >"$run_dir/cloudflared-after.txt"
if ! journalctl -k --utc --since "$steady_start_journal" --no-pager >"$run_dir/kernel-after.log" 2>"$run_dir/kernel-after.err"; then
  printf 'KERNEL_JOURNAL_UNAVAILABLE\n' >>"$run_dir/kernel-after.log"
  printf 'journalctl failed for the steady window; see kernel-after.err\n' >&2
fi
if [[ -n "$events_pid" ]]; then
  kill "$events_pid" 2>/dev/null || true
  wait "$events_pid" 2>/dev/null || true
  events_pid=""
fi

python3 - "$run_dir" "$steady_elapsed" "$steady_samples" "$required_steady_seconds" <<'PY'
import csv
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

run_dir = Path(sys.argv[1])
steady_elapsed = int(sys.argv[2])
required_samples = int(sys.argv[3])
required_steady_seconds = int(sys.argv[4])

with (run_dir / "host-memory.tsv").open(encoding="utf-8") as stream:
    rows = list(csv.DictReader(stream, delimiter="\t"))
baseline = [row for row in rows if row["phase"] == "baseline"]
steady = [row for row in rows if row["phase"] == "steady"]
mem_available = [int(row["MemAvailable_kB"]) for row in steady]
swap_used = [int(row["SwapTotal_kB"]) - int(row["SwapFree_kB"]) for row in steady]
baseline_swap = [int(row["SwapTotal_kB"]) - int(row["SwapFree_kB"]) for row in baseline]
baseline_gaps = [int(current["elapsed_seconds"]) - int(previous["elapsed_seconds"]) for previous, current in zip(baseline, baseline[1:])]
steady_gaps = [int(current["elapsed_seconds"]) - int(previous["elapsed_seconds"]) for previous, current in zip(steady, steady[1:])]
swap_in_delta = int(steady[-1]["pswpin"]) - int(steady[0]["pswpin"]) if steady else 0
swap_out_delta = int(steady[-1]["pswpout"]) - int(steady[0]["pswpout"]) if steady else 0
swap_activity_streak = 0
max_swap_activity_streak = 0
for previous, current in zip(steady, steady[1:]):
    interval_active = (
        int(current["pswpin"]) > int(previous["pswpin"])
        or int(current["pswpout"]) > int(previous["pswpout"])
    )
    swap_activity_streak = swap_activity_streak + 1 if interval_active else 0
    max_swap_activity_streak = max(max_swap_activity_streak, swap_activity_streak)

with (run_dir / "health.tsv").open(encoding="utf-8") as stream:
    health = list(csv.DictReader(stream, delimiter="\t"))
health_ok = all(
    row["backend_http"] == "200"
    and row["frontend_http"] == "200"
    and row["proxy_http"] == "200"
    and row["postgres_exit"] == "0"
    and '"status":"UP"' in row["backend_body"]
    for row in health
)

start_inspect = json.loads((run_dir / "forgepilot-containers-start.json").read_text(encoding="utf-8"))
end_inspect = json.loads((run_dir / "forgepilot-containers-end.json").read_text(encoding="utf-8"))
start_restarts = {item["Name"].lstrip("/"): int(item.get("RestartCount", 0)) for item in start_inspect}
end_restarts = {item["Name"].lstrip("/"): int(item.get("RestartCount", 0)) for item in end_inspect}
restart_delta = {name: end_restarts.get(name, 0) - count for name, count in start_restarts.items()}
restart_ok = all(delta == 0 for delta in restart_delta.values())
oom_killed = {item["Name"].lstrip("/"): bool(item.get("State", {}).get("OOMKilled")) for item in end_inspect}

existing_before = json.loads((run_dir / "existing-containers-before.json").read_text(encoding="utf-8"))
existing_after = json.loads((run_dir / "existing-containers-after.json").read_text(encoding="utf-8"))
existing_before_state = {
    item["Name"].lstrip("/"): (
        bool(item.get("State", {}).get("Running")),
        int(item.get("RestartCount", 0)),
        item.get("State", {}).get("StartedAt"),
    )
    for item in existing_before
}
existing_after_state = {
    item["Name"].lstrip("/"): (
        bool(item.get("State", {}).get("Running")),
        int(item.get("RestartCount", 0)),
        item.get("State", {}).get("StartedAt"),
    )
    for item in existing_after
}
existing_services_ok = all(
    existing_after_state.get(name) == before_state
    for name, before_state in existing_before_state.items()
)
cloudflared_after = (run_dir / "cloudflared-after.txt").read_text(encoding="utf-8", errors="replace")
cloudflared_before = (run_dir / "cloudflared-before.txt").read_text(encoding="utf-8", errors="replace")
def property_value(text, name):
    for line in text.splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1]
    return None
cloudflared_ok = (
    property_value(cloudflared_after, "ActiveState") == "active"
    and property_value(cloudflared_after, "NRestarts") == property_value(cloudflared_before, "NRestarts")
)

event_lines = (run_dir / "docker-events.jsonl").read_text(encoding="utf-8", errors="replace").splitlines()
bad_events = []
for line in event_lines:
    try:
        event = json.loads(line)
    except json.JSONDecodeError:
        continue
    if event.get("Action") in {"oom", "die", "restart", "kill"}:
        bad_events.append(event)

kernel_text = (run_dir / "kernel-after.log").read_text(encoding="utf-8", errors="replace").lower()
oom_markers = ("out of memory", "oom-kill", "killed process")
kernel_oom = any(marker in kernel_text for marker in oom_markers)
kernel_journal_ok = "kernel_journal_unavailable" not in kernel_text

with (run_dir / "docker-stats.jsonl").open(encoding="utf-8") as stream:
    stats = [json.loads(line) for line in stream if line.strip()]
forgepilot_memory_percent = []
for record in stats:
    if record.get("phase") != "steady":
        continue
    stat = record.get("stat", {})
    name = stat.get("Name", "")
    if "forgepilot-phase1-cap-" not in name:
        continue
    raw = str(stat.get("MemPerc", "0")).strip().rstrip("%")
    try:
        forgepilot_memory_percent.append(float(raw))
    except ValueError:
        pass

with (run_dir / "postgres-version.csv").open(encoding="utf-8") as stream:
    postgres_version_rows = list(csv.DictReader(stream))
postgres_version_ok = len(postgres_version_rows) == 1 and postgres_version_rows[0].get("postgres_15_or_newer") in {"t", "true"}
pgvector_text = (run_dir / "pgvector.csv").read_text(encoding="utf-8")
pgvector_ok = "0.8.6" in pgvector_text and "1" in pgvector_text
with (run_dir / "flyway.csv").open(encoding="utf-8") as stream:
    flyway_rows = list(csv.DictReader(stream))
flyway_ok = bool(flyway_rows) and all(row.get("success") in {"t", "true"} for row in flyway_rows)
application_table_lines = [line for line in (run_dir / "application-tables.csv").read_text(encoding="utf-8").splitlines() if line.strip()]
empty_schema_ok = application_table_lines == ["table_name"]

pss_by_service_time = defaultdict(lambda: defaultdict(int))
with (run_dir / "process-smaps.tsv").open(encoding="utf-8") as stream:
    for row in csv.DictReader(stream, delimiter="\t"):
        if row["phase"] != "steady":
            continue
        pss_by_service_time[row["service"]][row["timestamp"]] += int(row["Pss_kB"])

pss_summary = {}
for service, values in sorted(pss_by_service_time.items()):
    ordered = [value for _, value in sorted(values.items())]
    if ordered:
        pss_summary[service] = {
            "minKiB": min(ordered),
            "medianKiB": int(statistics.median(ordered)),
            "maxKiB": max(ordered),
        }

checks = {
    "baseline_duration": len(baseline) >= 21 and int(baseline[-1]["elapsed_seconds"]) >= 300,
    "baseline_cadence": len(baseline_gaps) >= 20 and all(14 <= gap <= 16 for gap in baseline_gaps),
    "steady_duration": steady_elapsed >= required_steady_seconds,
    "steady_samples": len(steady) >= required_samples,
    "steady_cadence": len(steady_gaps) >= required_samples - 1 and all(14 <= gap <= 16 for gap in steady_gaps),
    "mem_available": bool(mem_available) and min(mem_available) >= 1048576,
    "health": bool(health) and health_ok,
    "restarts": restart_ok,
    "oom_killed": not any(oom_killed.values()),
    "existing_services": existing_services_ok and cloudflared_ok,
    "docker_events": not bad_events,
    "kernel_oom": kernel_journal_ok and not kernel_oom,
    "swap_growth": bool(swap_used) and bool(baseline_swap) and max(swap_used) - int(statistics.median(baseline_swap)) <= 65536,
    "swap_activity": max_swap_activity_streak < 3,
    "container_headroom": bool(forgepilot_memory_percent) and max(forgepilot_memory_percent) <= 85.0,
    "postgres_version": postgres_version_ok,
    "pgvector": pgvector_ok,
    "flyway": flyway_ok,
    "empty_schema": empty_schema_ok,
}
status = "PASS" if all(checks.values()) else "FAIL"
summary = {
    "status": status,
    "checks": checks,
    "baselineSamples": len(baseline),
    "baselineElapsedSeconds": int(baseline[-1]["elapsed_seconds"]) if baseline else 0,
    "steadySamples": len(steady),
    "steadyElapsedSeconds": steady_elapsed,
    "sampleCadenceSeconds": {
        "baselineMin": min(baseline_gaps) if baseline_gaps else None,
        "baselineMedian": statistics.median(baseline_gaps) if baseline_gaps else None,
        "baselineMax": max(baseline_gaps) if baseline_gaps else None,
        "steadyMin": min(steady_gaps) if steady_gaps else None,
        "steadyMedian": statistics.median(steady_gaps) if steady_gaps else None,
        "steadyMax": max(steady_gaps) if steady_gaps else None,
    },
    "memAvailableKiB": {
        "min": min(mem_available) if mem_available else None,
        "median": int(statistics.median(mem_available)) if mem_available else None,
        "max": max(mem_available) if mem_available else None,
        "final": mem_available[-1] if mem_available else None,
    },
    "swapUsedKiB": {
        "baselineMedian": int(statistics.median(baseline_swap)) if baseline_swap else None,
        "steadyMin": min(swap_used) if swap_used else None,
        "steadyMax": max(swap_used) if swap_used else None,
        "steadyFinal": swap_used[-1] if swap_used else None,
        "pageInDelta": swap_in_delta,
        "pageOutDelta": swap_out_delta,
        "maxConsecutiveActiveIntervals": max_swap_activity_streak,
    },
    "healthSamples": len(health),
    "restartDelta": restart_delta,
    "oomKilled": oom_killed,
    "existingServicesPreserved": existing_services_ok and cloudflared_ok,
    "maxForgePilotContainerMemoryPercent": max(forgepilot_memory_percent) if forgepilot_memory_percent else None,
    "badDockerEvents": bad_events,
    "kernelOomMarkersFound": kernel_oom,
    "pssByService": pss_summary,
}
(run_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")

lines = [
    "# Phase 1 capacity result",
    "",
    f"**{status}**",
    "",
    f"- Baseline: {summary['baselineSamples']} samples, {summary['baselineElapsedSeconds']} seconds.",
    f"- Steady: {summary['steadySamples']} samples, {summary['steadyElapsedSeconds']} seconds.",
    f"- Sample cadence min/median/max: baseline {summary['sampleCadenceSeconds']['baselineMin']} / {summary['sampleCadenceSeconds']['baselineMedian']} / {summary['sampleCadenceSeconds']['baselineMax']} seconds; steady {summary['sampleCadenceSeconds']['steadyMin']} / {summary['sampleCadenceSeconds']['steadyMedian']} / {summary['sampleCadenceSeconds']['steadyMax']} seconds.",
    f"- MemAvailable min/median/final: {summary['memAvailableKiB']['min']} / {summary['memAvailableKiB']['median']} / {summary['memAvailableKiB']['final']} KiB.",
    f"- Swap used baseline median / steady max / final: {summary['swapUsedKiB']['baselineMedian']} / {summary['swapUsedKiB']['steadyMax']} / {summary['swapUsedKiB']['steadyFinal']} KiB.",
    f"- Health samples: {summary['healthSamples']}; restart delta: {summary['restartDelta']}.",
    f"- Docker bad events: {len(bad_events)}; kernel OOM markers: {kernel_oom}.",
    "",
    "## Checks",
    "",
]
for name, passed in checks.items():
    lines.append(f"- [{'x' if passed else ' '}] {name}")
lines.extend(["", "Synthetic/reference evaluation was not run during this capacity window.", ""])
(run_dir / "summary.md").write_text("\n".join(lines), encoding="utf-8")
print(status)
PY
capacity_status="$(jq -r .status "$run_dir/summary.json")"
printf '%s\n' "$capacity_status" >"$run_dir/run-status.txt"

jq -n \
  --arg runId "$run_id" \
  --arg project "$project" \
  --arg startUtc "$(awk -F= '$1 == "start_utc" {print $2}' "$run_dir/host.txt")" \
  --arg steadyStart "$steady_start_utc" \
  --arg steadyEnd "$steady_end_utc" \
  --arg status "$capacity_status" \
  --arg gitHead "$(cat "$run_dir/git-head.txt")" \
  --arg composeSha256 "$(awk '{print $1}' "$run_dir/compose.sha256")" \
  --argjson baselineSamples "$baseline_samples" \
  --argjson steadySamples "$steady_samples" \
  --argjson sampleIntervalSeconds "$sample_interval" \
  --argjson warmupSeconds "$warmup_seconds" \
  --argjson requiredSteadySeconds "$required_steady_seconds" \
  --argjson steadyElapsedSeconds "$steady_elapsed" \
  '{runId:$runId,project:$project,startUtc:$startUtc,steadyStartUtc:$steadyStart,steadyEndUtc:$steadyEnd,status:$status,gitHead:$gitHead,composeSha256:$composeSha256,baselineSamples:$baselineSamples,steadySamples:$steadySamples,sampleIntervalSeconds:$sampleIntervalSeconds,warmupSeconds:$warmupSeconds,requiredSteadySeconds:$requiredSteadySeconds,steadyElapsedSeconds:$steadyElapsedSeconds,elapsedClock:"/proc/uptime monotonic milliseconds",samplingSchedule:"monotonic deadline",claimBoundary:"Four-minute empty-stack capacity evidence only; not a long-term stability claim."}' \
  >"$run_dir/metadata.json"

finalized=1
printf 'Capacity run %s finished with %s. Evidence: %s\n' "$run_id" "$capacity_status" "$run_dir"
if [[ "$capacity_status" != "PASS" ]]; then
  exit 1
fi
