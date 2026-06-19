create table if not exists agent_run (
    id bigserial primary key,
    project_id bigint not null,
    repository_id bigint not null,
    pull_request_id bigint,
    trigger_key varchar(200) not null,
    head_sha varchar(80) not null,
    status varchar(40) not null,
    current_step_sequence integer not null default 0,
    cancellation_requested boolean not null default false,
    version bigint not null default 0,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create unique index if not exists uq_agent_run_trigger_key on agent_run(trigger_key);
create index if not exists idx_agent_run_status_updated on agent_run(status, updated_at);
create index if not exists idx_agent_run_project on agent_run(project_id);

create table if not exists agent_step (
    id bigserial primary key,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    sequence_no integer not null,
    step_type varchar(40) not null,
    status varchar(24) not null,
    attempt integer not null default 0,
    input_summary text,
    output_summary text,
    error_message text,
    started_at timestamp(6) with time zone,
    finished_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create unique index if not exists uq_agent_step_sequence
    on agent_step(agent_run_id, sequence_no);
create index if not exists idx_agent_step_run
    on agent_step(agent_run_id, sequence_no);

create table if not exists tool_invocation (
    id bigserial primary key,
    invocation_key varchar(160) not null,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    agent_step_id bigint not null references agent_step(id) on delete cascade,
    tool_name varchar(120) not null,
    input_json text not null,
    output_json text,
    status varchar(24) not null,
    duration_ms bigint not null default 0,
    tool_calls integer not null default 1,
    input_bytes bigint not null default 0,
    output_bytes bigint not null default 0,
    error_message text,
    correlation_id varchar(128),
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create unique index if not exists uq_tool_invocation_key
    on tool_invocation(invocation_key);
create index if not exists idx_tool_invocation_run
    on tool_invocation(agent_run_id, agent_step_id);
