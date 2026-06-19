alter table review_task add column if not exists pull_request_id bigint;
alter table review_task add column if not exists base_commit_id_normalized varchar(80) not null default '';
update review_task
set base_commit_id_normalized = coalesce(base_commit_id, '')
where base_commit_id_normalized is null or base_commit_id_normalized = '';

alter table user_account add column if not exists session_version integer not null default 0;

alter table ai_call_log add column if not exists prompt_tokens integer not null default 0;
alter table ai_call_log add column if not exists completion_tokens integer not null default 0;
alter table ai_call_log add column if not exists total_tokens integer not null default 0;

alter table feedback add column if not exists updated_at timestamp(6) with time zone;
update feedback set updated_at = created_at where updated_at is null;
alter table feedback alter column updated_at set not null;

delete from feedback f
using feedback dup
where f.issue_id = dup.issue_id
  and f.user_id = dup.user_id
  and f.id < dup.id;

create index if not exists idx_review_task_pull_request on review_task(pull_request_id);
create unique index if not exists uq_review_task_idempotency
    on review_task(project_id, repository_id, commit_id, base_commit_id_normalized, branch_name);
create unique index if not exists uq_feedback_issue_user on feedback(issue_id, user_id);
