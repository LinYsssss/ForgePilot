-- 对比审查前置:把"参与审查的知识文档集合"纳入审查任务的幂等唯一键。
-- doc_set_key = 排序去重后文档 id 串的 SHA-256(空集为空串),由应用层写入。
-- 存量行回填为空串:它们的历史文档组合不再参与新键比较;若某历史提交曾带文档,
-- 新的同组合请求会各自成为新任务而不是撞上旧行 —— 一次性、良性的语义迁移。
alter table review_task add column if not exists doc_set_key varchar(64) not null default '';

drop index if exists uq_review_task_idempotency;
create unique index if not exists uq_review_task_idempotency
    on review_task(project_id, repository_id, commit_id, base_commit_id_normalized, branch_name, doc_set_key);
