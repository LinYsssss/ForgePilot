#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../../../../" && pwd)"
evidence_root="$repo_root/.trellis/tasks/08-21-batch-3-review-engine-human-loop/evidence/capacity"
run_id="${1:-$(date -u +%Y%m%dT%H%M%SZ)-$(git -C "$repo_root" rev-parse --short=12 HEAD)}"
project="forgepilot-batch3-cap-${run_id,,}"
run_dir="$evidence_root/$run_id"
backend_port="${FORGEPILOT_BACKEND_PORT:-38080}"
frontend_port="${FORGEPILOT_FRONTEND_PORT:-38081}"
backend_url="http://127.0.0.1:$backend_port"
stack_started=0
finalized=0

if [[ ! "$run_id" =~ ^[0-9]{8}T[0-9]{6}Z-[a-z0-9][a-z0-9._-]*$ ]]; then
  printf 'Invalid run id: %s\n' "$run_id" >&2
  exit 2
fi
if [[ -e "$run_dir" ]]; then
  printf 'Refusing to overwrite capacity evidence: %s\n' "$run_dir" >&2
  exit 2
fi

mkdir -p "$run_dir"
cd "$repo_root"
export FORGEPILOT_BACKEND_PORT="$backend_port"
export FORGEPILOT_FRONTEND_PORT="$frontend_port"
compose=(docker compose --file compose.yaml --file "$evidence_root/compose.capacity.yaml" --project-name "$project")

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if ((finalized == 0)); then
    printf 'INVALID\texit=%s\ttime=%s\n' "$status" "$(date -u +%FT%TZ)" >"$run_dir/run-status.txt"
  fi
  if ((stack_started == 1)) && [[ "$project" == forgepilot-batch3-cap-* ]]; then
    "${compose[@]}" down --volumes --remove-orphans >>"$run_dir/commands.log" 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

metric_value() {
  local name=$1
  local tag=${2:-}
  local url="$backend_url/actuator/metrics/$name"
  if [[ -n "$tag" ]]; then
    url="$url?tag=$tag"
  fi
  curl --fail --silent --show-error --max-time 5 "$url" | jq -r '.measurements[0].value'
}

review_statuses() {
  "${compose[@]}" exec --no-TTY postgres sh -ec \
    'psql -XAt --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --command "select string_agg(status, '\''|'\'' order by id) from review"'
}

sample() {
  local index=$1
  local timestamp heap_used heap_committed direct_used hikari_active hikari_pending statuses
  timestamp="$(date -u +%FT%TZ)"
  heap_used="$(metric_value jvm.memory.used area:heap)"
  heap_committed="$(metric_value jvm.memory.committed area:heap)"
  direct_used="$(metric_value jvm.buffer.memory.used id:direct)"
  hikari_active="$(metric_value hikaricp.connections.active)"
  hikari_pending="$(metric_value hikaricp.connections.pending)"
  statuses="$(review_statuses)"
  jq -cn --arg ts "$timestamp" --argjson sample "$index" \
    --argjson heapUsed "$heap_used" --argjson heapCommitted "$heap_committed" \
    --argjson directUsed "$direct_used" --argjson hikariActive "$hikari_active" \
    --argjson hikariPending "$hikari_pending" --arg statuses "$statuses" \
    '{timestamp:$ts,sample:$sample,heapUsedBytes:$heapUsed,heapCommittedBytes:$heapCommitted,directUsedBytes:$directUsed,hikariActive:$hikariActive,hikariPending:$hikariPending,statuses:$statuses}' \
    >>"$run_dir/runtime.jsonl"
  docker stats --no-stream --format '{{json .}}' \
    "${project}-backend-1" "${project}-postgres-1" "${project}-frontend-1" "${project}-ai-stub-1" \
    | jq -c --arg ts "$timestamp" --argjson sample "$index" \
      '{timestamp:$ts,sample:$sample,stat:.}' >>"$run_dir/docker-stats.jsonl"
}

printf '%s\n' "$(date -u +%FT%TZ) docker compose build" >"$run_dir/commands.log"
{
  printf 'run_id=%s\nproject=%s\nstart_utc=%s\n' "$run_id" "$project" "$(date -u +%FT%TZ)"
  printf 'host_mem_total_bytes=%s\n' "$(awk '/^MemTotal:/ {print $2 * 1024}' /proc/meminfo)"
  printf 'host_swap_total_bytes=%s\n' "$(awk '/^SwapTotal:/ {print $2 * 1024}' /proc/meminfo)"
  uname -a
  docker version --format 'docker_server={{.Server.Version}}'
  docker compose version
} >"$run_dir/host.txt"
git rev-parse HEAD >"$run_dir/git-head.txt"
git status --short >"$run_dir/git-status.txt"
sha256sum compose.yaml "$evidence_root/compose.capacity.yaml" "$evidence_root/nginx.conf" \
  >"$run_dir/inputs.sha256"
"${compose[@]}" config --no-interpolate >"$run_dir/compose.rendered.yaml"

"${compose[@]}" build >"$run_dir/build.log" 2>&1
printf '%s\n' "$(date -u +%FT%TZ) docker compose up --detach --wait" >>"$run_dir/commands.log"
stack_started=1
"${compose[@]}" up --detach --no-build --wait >"$run_dir/compose-up.log" 2>&1
"${compose[@]}" ps --format json >"$run_dir/compose-ps-start.json"

# Two independent Reviews, each at both product cardinality bounds: 300 changed
# files and a stored canonical manifest close to the 4,000,000-character hard
# ingestion limit. Each patch stays below 60,000, so every byte is reviewed and
# the batcher must make many full-budget calls instead of truncating the input.
printf '%s\n' "$(date -u +%FT%TZ) seed two maximum-budget reviews" >>"$run_dir/commands.log"
"${compose[@]}" exec --no-TTY postgres sh -ec \
  'psql -X --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"' \
  >"$run_dir/seed.log" <<'SQL'
WITH leader AS (
    INSERT INTO user_account (username, password_hash)
    VALUES ('capacity-leader', 'not-used') RETURNING id
), project_row AS (
    INSERT INTO project (name, created_by, status)
    SELECT 'Batch 3 capacity', id, 'ACTIVE' FROM leader RETURNING id, created_by
), member_row AS (
    INSERT INTO project_member (project_id, user_id, role)
    SELECT id, created_by, 'LEADER' FROM project_row RETURNING project_id
), repository_row AS (
    INSERT INTO scm_repository (project_id, provider, instance_identity, external_id,
        api_base, encrypted_token, encrypted_secret)
    SELECT project_id, 'GITHUB', 'capacity.local', 'capacity/repository',
        'http://127.0.0.1', 'x', 'y' FROM member_row RETURNING project_id, id
), manifest_row AS (
    SELECT jsonb_agg(jsonb_build_object(
        'path', 'src/capacity/File' || lpad(n::text, 3, '0') || '.java',
        'changeType', 'modified',
        'patch', '@@ -1,1 +1,441 @@' || chr(10) || '-old' || chr(10)
            || repeat('+capacity payload 0123456789' || chr(10), 440)) ORDER BY n) AS manifest
    FROM generate_series(1, 300) AS n
), pull_requests AS (
    INSERT INTO pull_request (project_id, repository_id, external_number, title, base_sha,
        head_sha, review_input_fingerprint, changed_files,
        author_external_user_id, author_username)
    SELECT repository_row.project_id, repository_row.id, n,
        format('Capacity review %s', n), repeat('1', 40), repeat(n::text, 40),
        repeat(CASE n WHEN 1 THEN 'a' ELSE 'b' END, 64), manifest,
        format('capacity-%s', n), 'capacity'
    FROM repository_row CROSS JOIN manifest_row CROSS JOIN generate_series(1, 2) AS n
    RETURNING *
)
INSERT INTO review (project_id, pull_request_id, head_sha, review_input_fingerprint,
    context_snapshot_json, status, updated_at)
SELECT project_id, id, head_sha, review_input_fingerprint,
    jsonb_build_object(
        'requirement', null,
        'acceptanceCriteria', '[]'::jsonb,
        'pullRequest', jsonb_build_object(
            'provider', 'GITHUB', 'instance', 'capacity.local',
            'repository', 'capacity/repository', 'number', external_number,
            'baseSha', base_sha, 'headSha', head_sha,
            'inputFingerprint', review_input_fingerprint, 'title', title),
        'changedFiles', changed_files),
    'PENDING', now() - interval '1 day'
FROM pull_requests;

SELECT count(*) AS reviews,
       min(jsonb_array_length(context_snapshot_json->'changedFiles')) AS min_files,
       max(length((context_snapshot_json->'changedFiles')::text)) AS max_manifest_chars
FROM review;
SQL

deadline=$((SECONDS + 240))
sample_index=0
terminal=0
while ((SECONDS < deadline)); do
  sample_index=$((sample_index + 1))
  sample "$sample_index"
  statuses="$(review_statuses)"
  if [[ "$statuses" == "COMPLETED|COMPLETED" ]]; then
    terminal=1
    break
  fi
  if [[ "$statuses" == *FAILED* ]]; then
    break
  fi
  sleep 0.5
done

"${compose[@]}" exec --no-TTY postgres sh -ec \
  'psql -X --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --csv --command "
    select id,status,execution_attempt,engine,prompt_version,model,
           jsonb_array_length(context_snapshot_json->'\''changedFiles'\'') as files,
           length((context_snapshot_json->'\''changedFiles'\'')::text) as manifest_chars,
           length((context_snapshot_json->'\''changedFiles'\'')::text)
             - (jsonb_array_length(context_snapshot_json->'\''changedFiles'\'') * 6 - 1)
             as canonical_manifest_chars,
           summary_json->'\''coverage'\''->>'\''truncated'\'' as truncated
      from review order by id;
    select use_case,status,count(*) from ai_call_log group by use_case,status order by use_case,status;
  "' >"$run_dir/database-result.csv"
"${compose[@]}" logs --no-color backend ai-stub >"$run_dir/service-logs.txt" 2>&1
"${compose[@]}" ps --format json >"$run_dir/compose-ps-end.json"
docker inspect "${project}-backend-1" "${project}-postgres-1" \
  | jq '[.[] | {Name,State:{Status:.State.Status,Running:.State.Running,OOMKilled:.State.OOMKilled,ExitCode:.State.ExitCode},RestartCount,MemoryLimit:.HostConfig.Memory}]' \
  >"$run_dir/container-result.json"

peak_heap="$(jq -s 'map(.heapUsedBytes) | max' "$run_dir/runtime.jsonl")"
peak_direct="$(jq -s 'map(.directUsedBytes) | max' "$run_dir/runtime.jsonl")"
peak_hikari="$(jq -s 'map(.hikariActive) | max' "$run_dir/runtime.jsonl")"
peak_pending="$(jq -s 'map(.hikariPending) | max' "$run_dir/runtime.jsonl")"
oom_killed="$(jq '[.[].State.OOMKilled] | any' "$run_dir/container-result.json")"
review_rows="$(awk -F, 'NR > 1 && $1 ~ /^[0-9]+$/ {count++} END {print count + 0}' "$run_dir/database-result.csv")"
invalid_workload_rows="$(awk -F, 'NR > 1 && $1 ~ /^[0-9]+$/ && ($7 != 300 || $9 < 3900000 || $9 > 4000000 || $10 != "false") {count++} END {print count + 0}' "$run_dir/database-result.csv")"
canonical_manifest_chars="$(awk -F, 'NR > 1 && $1 ~ /^[0-9]+$/ {if ($9 > max) max=$9} END {print max + 0}' "$run_dir/database-result.csv")"

if ((terminal == 1)) && [[ "$oom_killed" == "false" ]] \
    && ((review_rows == 2)) && ((invalid_workload_rows == 0)); then
  verdict=PASS
else
  verdict=FAIL
fi
cat >"$run_dir/summary.md" <<EOF
# Batch 3 maximum-budget Review capacity

- Verdict: **$verdict**
- Host memory: $(awk -F= '/host_mem_total_bytes/ {print $2}' "$run_dir/host.txt") bytes
- Production limits: backend container 768 MiB; JVM heap 384 MiB; direct 128 MiB; metaspace 128 MiB; PostgreSQL 512 MiB / pool 5
- Workload: 2 concurrent Reviews; 300 files each; canonical manifest $canonical_manifest_chars characters each (required range 3,900,000..4,000,000); no truncation accepted
- Peak JVM heap used: $peak_heap bytes
- Peak JVM direct-buffer used: $peak_direct bytes
- Peak Hikari active/pending: $peak_hikari / $peak_pending
- Backend/PostgreSQL OOMKilled: $oom_killed
- Terminal states: $(review_statuses)
- Provider: local OpenAI-protocol deterministic stub; production AiGateway, batching, validation, persistence and fencing were used
- Holdout/evaluation corpus: not read and not used
EOF

printf '%s\texit=0\ttime=%s\n' "$verdict" "$(date -u +%FT%TZ)" >"$run_dir/run-status.txt"
finalized=1
if [[ "$verdict" != PASS ]]; then
  exit 1
fi
printf 'Capacity run passed: %s\n' "$run_dir"
