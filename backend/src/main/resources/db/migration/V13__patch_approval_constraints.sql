alter table approval_request alter column approver_id set not null;
create unique index if not exists uq_approval_patch_approver
    on approval_request(patch_candidate_id, approver_id);
