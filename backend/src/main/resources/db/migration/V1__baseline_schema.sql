create extension if not exists vector;

create table if not exists user_account (
    id bigserial primary key,
    username varchar(64) not null unique,
    password_hash varchar(255) not null,
    nickname varchar(64),
    role varchar(32) not null,
    status varchar(32) not null,
    session_version integer not null default 0,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create table if not exists project (
    id bigserial primary key,
    owner_id bigint not null,
    name varchar(128) not null,
    description text,
    default_branch varchar(128),
    status varchar(32) not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create table if not exists code_repository (
    id bigserial primary key,
    project_id bigint not null,
    repo_url varchar(512) not null,
    provider varchar(32) not null,
    default_branch varchar(128) not null,
    access_token text,
    local_path varchar(512),
    status varchar(32) not null,
    last_error text,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create table if not exists pull_request (
    id bigserial primary key,
    project_id bigint not null,
    repository_id bigint not null,
    provider varchar(32) not null,
    external_pr_id varchar(128),
    pr_number integer,
    title varchar(255) not null,
    author_name varchar(128),
    source_branch varchar(128) not null,
    target_branch varchar(128) not null,
    base_sha varchar(80) not null,
    head_sha varchar(80) not null,
    status varchar(32) not null,
    review_state varchar(32) not null,
    last_synced_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create table if not exists knowledge_document (
    id bigserial primary key,
    project_id bigint not null,
    uploader_id bigint not null,
    doc_type varchar(64) not null,
    file_name varchar(255) not null,
    content_text text,
    status varchar(32) not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create table if not exists knowledge_chunk (
    id bigserial primary key,
    document_id bigint not null,
    project_id bigint not null,
    doc_type varchar(64) not null,
    source_name varchar(255) not null,
    chunk_index integer not null,
    content text not null,
    embedding_json text,
    created_at timestamp(6) with time zone not null
);

create table if not exists review_task (
    id bigserial primary key,
    project_id bigint not null,
    repository_id bigint not null,
    commit_id varchar(80) not null,
    base_commit_id varchar(80),
    base_commit_id_normalized varchar(80) not null default '',
    branch_name varchar(128) not null,
    trigger_user_id bigint not null,
    pull_request_id bigint,
    status varchar(32) not null,
    retry_count integer not null,
    diff_text text,
    knowledge_doc_ids text,
    error_message text,
    started_at timestamp(6) with time zone,
    finished_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create table if not exists review_report (
    id bigserial primary key,
    task_id bigint not null,
    project_id bigint not null,
    commit_id varchar(80) not null,
    overall_risk varchar(32) not null,
    issue_count integer not null,
    summary text,
    raw_ai_response text,
    created_at timestamp(6) with time zone not null
);

create table if not exists review_issue (
    id bigserial primary key,
    report_id bigint not null,
    severity varchar(32) not null,
    category varchar(64) not null,
    file_path varchar(512),
    line_start integer,
    line_end integer,
    title varchar(255) not null,
    description text,
    impact text,
    evidence text,
    suggestion text,
    confidence double precision,
    created_at timestamp(6) with time zone not null
);

create table if not exists review_action (
    id bigserial primary key,
    project_id bigint not null,
    pull_request_id bigint not null,
    report_id bigint,
    actor_id bigint not null,
    action_type varchar(32) not null,
    reason text,
    requirement_text text,
    selected_issue_ids text,
    created_at timestamp(6) with time zone not null
);

create table if not exists feedback (
    id bigserial primary key,
    issue_id bigint not null,
    user_id bigint not null,
    feedback_type varchar(32) not null,
    comment text,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create table if not exists mq_task_log (
    id bigserial primary key,
    task_id bigint,
    message_id varchar(128) not null,
    exchange_name varchar(128) not null,
    routing_key varchar(128) not null,
    queue_name varchar(128) not null,
    payload text,
    status varchar(32) not null,
    retry_count integer not null,
    error_message text,
    created_at timestamp(6) with time zone not null
);

create table if not exists ai_call_log (
    id bigserial primary key,
    project_id bigint,
    task_id bigint,
    request_type varchar(64) not null,
    provider varchar(64) not null,
    model varchar(128) not null,
    prompt_chars integer not null,
    response_chars integer not null,
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    total_tokens integer not null default 0,
    latency_ms bigint not null,
    status varchar(32) not null,
    error_message text,
    created_at timestamp(6) with time zone not null
);

create table if not exists knowledge_chunk_vector (
    chunk_id bigint primary key,
    project_id bigint not null,
    embedding vector not null
);

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

delete from knowledge_chunk_vector kv
where not exists (
    select 1 from knowledge_chunk kc where kc.id = kv.chunk_id
);

alter table knowledge_chunk_vector
    drop constraint if exists fk_knowledge_chunk_vector_chunk;
alter table knowledge_chunk_vector
    add constraint fk_knowledge_chunk_vector_chunk
    foreign key (chunk_id) references knowledge_chunk(id) on delete cascade;

delete from feedback f
using feedback dup
where f.issue_id = dup.issue_id
  and f.user_id = dup.user_id
  and f.id < dup.id;

create index if not exists idx_project_owner on project(owner_id);
create index if not exists idx_code_repository_project on code_repository(project_id);
create index if not exists idx_pull_request_project on pull_request(project_id);
create index if not exists idx_pull_request_repo on pull_request(repository_id);
create index if not exists idx_pull_request_review_state on pull_request(project_id, review_state);
create index if not exists idx_knowledge_document_project on knowledge_document(project_id);
create index if not exists idx_knowledge_chunk_document on knowledge_chunk(document_id);
create index if not exists idx_knowledge_chunk_project on knowledge_chunk(project_id);
create index if not exists idx_review_task_project on review_task(project_id);
create index if not exists idx_review_task_pull_request on review_task(pull_request_id);
create index if not exists idx_review_task_unique_lookup
    on review_task(project_id, repository_id, commit_id, base_commit_id, branch_name);
create unique index if not exists uq_review_task_idempotency
    on review_task(project_id, repository_id, commit_id, base_commit_id_normalized, branch_name);
create index if not exists idx_review_report_project on review_report(project_id);
create index if not exists idx_review_issue_report on review_issue(report_id);
create index if not exists idx_review_action_project on review_action(project_id);
create index if not exists idx_review_action_pull_request on review_action(pull_request_id);
create index if not exists idx_feedback_issue on feedback(issue_id);
create unique index if not exists uq_feedback_issue_user on feedback(issue_id, user_id);
create index if not exists idx_mq_task_log_task on mq_task_log(task_id);
create index if not exists idx_ai_call_log_project on ai_call_log(project_id);
create index if not exists idx_ai_call_log_task on ai_call_log(task_id);
create index if not exists idx_knowledge_chunk_vector_project on knowledge_chunk_vector(project_id);
