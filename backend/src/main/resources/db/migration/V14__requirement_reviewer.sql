ALTER TABLE requirement ADD COLUMN reviewer_id bigint;
ALTER TABLE requirement ADD CONSTRAINT fk_requirement_reviewer
    FOREIGN KEY (project_id, reviewer_id) REFERENCES project_member (project_id, user_id);
