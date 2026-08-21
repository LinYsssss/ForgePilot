#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/compose.yaml"
project="${1:-forgepilot-phase1-smoke-$(date -u +%Y%m%d%H%M%S)-$$}"
export FORGEPILOT_SPRING_PROFILES_ACTIVE=""

if [[ ! "$project" =~ ^forgepilot-phase1-[a-z0-9][a-z0-9_-]*$ ]]; then
  printf 'Refusing unsafe Compose project name: %s\n' "$project" >&2
  printf 'Use a unique lowercase name beginning with forgepilot-phase1-.\n' >&2
  exit 2
fi

cd "$repo_root"
compose=(docker compose --file "$compose_file" --project-name "$project")

existing_containers="$(docker ps --all --quiet --filter "label=com.docker.compose.project=$project")"
existing_volumes="$(docker volume ls --quiet --filter "label=com.docker.compose.project=$project")"
existing_networks="$(docker network ls --quiet --filter "label=com.docker.compose.project=$project")"
if [[ -n "$existing_containers" || -n "$existing_volumes" || -n "$existing_networks" ]]; then
  printf 'Refusing to reuse existing Compose project or volumes: %s\n' "$project" >&2
  exit 2
fi

cleanup() {
  local status=$?
  local cleanup_status=0
  trap - EXIT

  if ((status != 0)); then
    "${compose[@]}" ps >&2 || true
    "${compose[@]}" logs --no-color >&2 || true
  fi

  "${compose[@]}" down --volumes --remove-orphans || cleanup_status=$?
  if ((status == 0 && cleanup_status != 0)); then
    status=$cleanup_status
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mapfile -t services < <("${compose[@]}" config --services | sort)
expected_services=(backend frontend postgres)
if [[ "${services[*]}" != "${expected_services[*]}" ]]; then
  printf 'Unexpected Compose services: %s\n' "${services[*]}" >&2
  exit 1
fi

"${compose[@]}" config --quiet
rendered_config="$(mktemp)"
"${compose[@]}" config --format json >"$rendered_config"
if python3 -c '
import json
import re
import sys
from pathlib import Path

with open(sys.argv[1], encoding="utf-8") as stream:
    document = json.load(stream)
services = document.get("services", {})
expected = {"postgres": 512 * 1024 * 1024, "backend": 768 * 1024 * 1024, "frontend": 64 * 1024 * 1024}
if set(services) != set(expected):
    raise SystemExit(f"unexpected services in rendered config: {sorted(services)}")
for name, memory in expected.items():
    service = services[name]
    actual_memory = service.get("mem_limit", 0)
    if int(actual_memory) != memory:
        raise SystemExit(f"{name} has unexpected mem_limit: {actual_memory}")
    if not service.get("healthcheck", {}).get("test"):
        raise SystemExit(f"{name} is missing an explicit healthcheck")
postgres_image = services["postgres"].get("image")
expected_image = "pgvector/pgvector:0.8.6-pg15-bookworm@sha256:a947c45cdc5906a1bc951f20a8709e321256343ee0f251e4ae00b5e7def4e6da"
if postgres_image != expected_image:
    raise SystemExit(f"postgres image is not pinned to the approved digest: {postgres_image}")
if set(document.get("volumes", {})) != {"postgres-data"}:
    raise SystemExit("expected exactly one named postgres-data volume")
nginx = Path(sys.argv[2]).read_text(encoding="utf-8")
api_location = re.search(r"location\s+/api/\s*\{(?P<body>.*?)\n\s*\}", nginx, flags=re.DOTALL)
if api_location is None:
    raise SystemExit("frontend nginx is missing the generic /api/ proxy")
body = api_location.group("body")
if "proxy_pass http://backend:8080;" not in body or "proxy_pass http://backend:8080/;" in body:
    raise SystemExit("generic /api/ proxy must preserve the /api prefix")
' "$rendered_config" "$repo_root/frontend/nginx.conf"
then
  rm -f -- "$rendered_config"
  printf 'Rendered Compose static contract passed.\n'
else
  rm -f -- "$rendered_config"
  printf 'Rendered Compose static contract failed.\n' >&2
  exit 1
fi
"${compose[@]}" build
"${compose[@]}" up --detach --wait --wait-timeout 240
"${compose[@]}" ps

backend_address="$("${compose[@]}" port backend 8080)"
frontend_address="$("${compose[@]}" port frontend 8080)"

backend_health="$(curl --fail --silent --show-error --max-time 10 "http://$backend_address/actuator/health")"
proxy_health="$(curl --fail --silent --show-error --max-time 10 "http://$frontend_address/api/actuator/health")"
metrics_status="$(curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}' "http://$backend_address/actuator/metrics")"
curl --fail --silent --show-error --max-time 10 "http://$frontend_address/healthz" >/dev/null
curl --fail --silent --show-error --max-time 10 "http://$frontend_address/" >/dev/null

if [[ "$backend_health" != *'"status":"UP"'* || "$proxy_health" != *'"status":"UP"'* ]]; then
  printf 'Backend health contract did not report UP.\n' >&2
  exit 1
fi
if [[ "$metrics_status" != "404" ]]; then
  printf 'Default backend profile unexpectedly exposes Actuator metrics (HTTP %s).\n' "$metrics_status" >&2
  exit 1
fi

query_postgres() {
  local sql=$1
  "${compose[@]}" exec --no-TTY postgres sh -ec \
    'psql -X --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --tuples-only --no-align --command "$1"' \
    phase1-smoke "$sql"
}

postgres_15_or_newer="$(query_postgres "select current_setting('server_version_num')::integer >= 150000;")"
vector_version="$(query_postgres "select extversion from pg_extension where extname = 'vector';")"
vector_distance="$(query_postgres "select '[1,2,3]'::vector <-> '[1,2,4]'::vector;")"
flyway_v1="$(query_postgres "select success from flyway_schema_history where version = '1';")"
failed_migrations="$(query_postgres "select count(*) from flyway_schema_history where success is false;")"
application_tables="$(query_postgres "select string_agg(table_name, ',' order by table_name collate \"C\") from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' and table_name <> 'flyway_schema_history';")"

# Batch 1 migrations V2/V3 and batch 2 migrations V4/V5 create exactly these
# thirteen. The remaining three of the sixteen tables (review, finding,
# finding_event) arrive with their own authorized batch, so an extra table here
# means something was added outside the plan. Sorted with the C collation so the
# comparison does not depend on the container's locale.
expected_tables='acceptance_criterion,ai_call_log,knowledge_chunk,knowledge_document,project,project_member,pull_request,pull_request_requirement_event,requirement,requirement_attachment,requirement_revision,scm_repository,user_account'

[[ "$postgres_15_or_newer" == "t" ]] || {
  printf 'PostgreSQL server is older than 15.\n' >&2
  exit 1
}
[[ -n "$vector_version" && "$vector_distance" == "1" ]] || {
  printf 'pgvector extension contract failed.\n' >&2
  exit 1
}
[[ "$flyway_v1" == "t" ]] || {
  printf 'Flyway foundation migration was not successful.\n' >&2
  exit 1
}
[[ "$failed_migrations" == "0" ]] || {
  printf 'Flyway reported %s failed migrations.\n' "$failed_migrations" >&2
  exit 1
}
[[ "$application_tables" == "$expected_tables" ]] || {
  printf 'Unexpected application schema.\n  expected: %s\n  found:    %s\n' \
    "$expected_tables" "$application_tables" >&2
  exit 1
}

printf 'Compose smoke passed for project %s (pgvector %s, %s application tables).\n' \
  "$project" "$vector_version" "$(printf '%s' "$application_tables" | awk -F, '{print NF}')"
