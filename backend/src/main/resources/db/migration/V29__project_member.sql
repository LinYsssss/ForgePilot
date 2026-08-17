-- ForgePilot P1a(R1):项目成员与 3 角色。owner 恒为唯一 LEADER,ownerId 与成员行由
-- 应用层单事务保持一致;这里把存量项目的 owner 幂等回填为 LEADER 行,保证放宽
-- requireRead(owner-only → 任意成员)后单人项目行为零变化。
-- user_id 不加 FK:与 review_feedback.reporter_id 同口径(用户删除策略未定,不预设级联)。
create table if not exists project_member (
    id bigserial primary key,
    project_id bigint not null references project(id) on delete cascade,
    user_id bigint not null,
    role varchar(32) not null,
    created_at timestamp(6) with time zone not null,
    constraint uq_project_member unique (project_id, user_id),
    constraint ck_project_member_role check (role in ('LEADER', 'DEVELOPER', 'REVIEWER'))
);

create index if not exists idx_project_member_user on project_member(user_id);

insert into project_member (project_id, user_id, role, created_at)
select p.id, p.owner_id, 'LEADER', now()
from project p
where not exists (
    select 1 from project_member m where m.project_id = p.id and m.user_id = p.owner_id
);
