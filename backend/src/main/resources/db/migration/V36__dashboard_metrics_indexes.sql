-- P7 workbench and fixed-window metrics projection indexes. Forward-only and additive.
create index if not exists idx_requirement_project_assignee_status_updated
    on requirement(project_id, assignee_id, status, updated_at);
create index if not exists idx_agent_run_project_created
    on agent_run(project_id, created_at);
create index if not exists idx_pull_request_project_status_review_updated
    on pull_request(project_id, status, review_state, updated_at);
create index if not exists idx_ai_call_log_project_created
    on ai_call_log(project_id, created_at);
create index if not exists idx_agent_finding_assignee_lifecycle_created
    on agent_finding(assignee_id, lifecycle_status, created_at);
