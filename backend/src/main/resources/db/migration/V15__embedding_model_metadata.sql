alter table knowledge_chunk
    add column if not exists embedding_provider varchar(80);

alter table knowledge_chunk
    add column if not exists embedding_model varchar(160);

alter table knowledge_chunk
    add column if not exists embedding_version varchar(160);

alter table knowledge_chunk
    add column if not exists embedding_dimension integer;

update knowledge_chunk
set embedding_provider = 'legacy-unknown',
    embedding_model = 'legacy-unknown',
    embedding_version = 'legacy-unknown'
where embedding_json is not null
  and embedding_provider is null;

create index if not exists idx_knowledge_chunk_embedding_compatibility
    on knowledge_chunk(project_id, embedding_provider, embedding_model, embedding_version, embedding_dimension);
