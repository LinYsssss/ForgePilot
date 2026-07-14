create table if not exists agent_outbox_event (
    id bigserial primary key,
    event_key varchar(200) not null,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    event_type varchar(60) not null,
    payload text not null,
    trace_id varchar(128),
    status varchar(24) not null,
    attempt_count integer not null default 0,
    next_attempt_at timestamp(6) with time zone not null,
    claimed_at timestamp(6) with time zone,
    sent_at timestamp(6) with time zone,
    last_error text,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create unique index if not exists uq_agent_outbox_event_key
    on agent_outbox_event(event_key);
create index if not exists idx_agent_outbox_available
    on agent_outbox_event(status, next_attempt_at, created_at);
create index if not exists idx_agent_outbox_run
    on agent_outbox_event(agent_run_id);
