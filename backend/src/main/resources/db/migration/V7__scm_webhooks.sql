-- V7 SCM installations and webhook deliveries for provider-triggered Agent runs.
-- Secrets are stored encrypted (AES-GCM via CryptoService); raw private payloads are never stored,
-- only a hash and a bounded sanitized preview. Identity is resolved from these rows, never trusted
-- from webhook payload fields.

create table if not exists scm_installation (
    id bigserial primary key,
    provider varchar(32) not null,
    external_installation_id varchar(255) not null,
    display_name varchar(255),
    app_id varchar(128),
    api_base_url varchar(512),
    project_id bigint,
    repository_id bigint,
    encrypted_webhook_secret text,
    encrypted_credential text,
    active boolean not null default true,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    constraint uq_scm_installation_provider_external unique (provider, external_installation_id)
);

create index if not exists idx_scm_installation_project on scm_installation(project_id);
create index if not exists idx_scm_installation_repository on scm_installation(repository_id);

create table if not exists scm_webhook_delivery (
    id bigserial primary key,
    provider varchar(32) not null,
    delivery_id varchar(255) not null,
    installation_id bigint,
    event_type varchar(64),
    action varchar(64),
    status varchar(32) not null,
    payload_hash varchar(80),
    payload_preview varchar(1000),
    agent_run_id bigint,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    constraint uq_scm_delivery_provider_delivery unique (provider, delivery_id),
    constraint fk_scm_delivery_installation
        foreign key (installation_id) references scm_installation(id) on delete set null
);

create index if not exists idx_scm_delivery_status on scm_webhook_delivery(status, updated_at);
create index if not exists idx_scm_delivery_installation on scm_webhook_delivery(installation_id);
