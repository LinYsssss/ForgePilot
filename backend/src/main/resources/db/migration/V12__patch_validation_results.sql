alter table patch_candidate add column if not exists apply_status varchar(24) not null default 'NOT_RUN';
alter table patch_candidate add column if not exists build_status varchar(24) not null default 'NOT_RUN';
alter table patch_candidate add column if not exists test_status varchar(24) not null default 'NOT_RUN';
alter table patch_candidate add column if not exists scan_status varchar(24) not null default 'NOT_RUN';
alter table patch_candidate add column if not exists target_disappeared boolean not null default false;
alter table patch_candidate add column if not exists validation_result_json text not null default '[]';
alter table patch_candidate add column if not exists validation_log text not null default '';
alter table patch_candidate add column if not exists validated_at timestamp(6) with time zone;
