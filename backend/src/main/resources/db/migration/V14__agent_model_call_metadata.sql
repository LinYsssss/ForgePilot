alter table agent_model_call
    add column if not exists call_purpose varchar(24) not null default 'GENERATE';

alter table agent_model_call
    add column if not exists finish_reason varchar(40) not null default 'UNKNOWN';

alter table agent_model_call
    add column if not exists latency_ms bigint not null default 0;

alter table agent_model_call
    add column if not exists response_hash varchar(64) not null
        default '0000000000000000000000000000000000000000000000000000000000000000';
