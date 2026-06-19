create table if not exists review_plan (
    id bigserial primary key,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    schema_version varchar(40) not null,
    model_response_json text not null,
    validated_plan_json text,
    validation_errors text,
    status varchar(24) not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create unique index if not exists uq_review_plan_run on review_plan(agent_run_id);
create index if not exists idx_review_plan_run on review_plan(agent_run_id);

create table if not exists agent_model_call (
    id bigserial primary key,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    provider varchar(80) not null,
    model varchar(160) not null,
    prompt_version varchar(40) not null,
    schema_version varchar(40) not null,
    input_tokens bigint not null default 0,
    output_tokens bigint not null default 0,
    status varchar(24) not null,
    failure_reason text,
    created_at timestamp(6) with time zone not null
);

create index if not exists idx_agent_model_call_run
    on agent_model_call(agent_run_id, created_at);
