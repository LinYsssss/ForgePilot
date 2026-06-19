create extension if not exists vector;

create table user_account (
    id bigserial primary key,
    username varchar(64) not null unique,
    password_hash varchar(255) not null,
    nickname varchar(64),
    role varchar(32) not null,
    status varchar(32) not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create table review_task (
    id bigserial primary key,
    project_id bigint not null,
    repository_id bigint not null,
    commit_id varchar(80) not null,
    base_commit_id varchar(80),
    branch_name varchar(128) not null,
    trigger_user_id bigint not null,
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

create table feedback (
    id bigserial primary key,
    issue_id bigint not null,
    user_id bigint not null,
    feedback_type varchar(32) not null,
    comment text,
    created_at timestamp(6) with time zone not null
);

create table ai_call_log (
    id bigserial primary key,
    project_id bigint,
    task_id bigint,
    request_type varchar(64) not null,
    provider varchar(64) not null,
    model varchar(128) not null,
    prompt_chars integer not null,
    response_chars integer not null,
    latency_ms bigint not null,
    status varchar(32) not null,
    error_message text,
    created_at timestamp(6) with time zone not null
);
