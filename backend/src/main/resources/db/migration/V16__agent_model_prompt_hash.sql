alter table agent_model_call
    add column if not exists prompt_hash varchar(64) not null
        default '0000000000000000000000000000000000000000000000000000000000000000';
