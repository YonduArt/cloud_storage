CREATE TABLE IF NOT EXISTS file_search_index (
    id BIGSERIAL PRIMARY KEY,
    file_id BIGINT NOT NULL UNIQUE REFERENCES stored_files(id) ON DELETE CASCADE,
    owner_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    content_type VARCHAR(40) NOT NULL,
    extracted_text TEXT NULL,
    description TEXT NULL,
    error_message VARCHAR(500) NULL,
    indexed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS file_embeddings (
    id BIGSERIAL PRIMARY KEY,
    file_id BIGINT NOT NULL REFERENCES stored_files(id) ON DELETE CASCADE,
    owner_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    embedding_type VARCHAR(30) NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    vector TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_file_search_index_owner_status ON file_search_index(owner_id, status);
CREATE INDEX IF NOT EXISTS idx_file_search_index_owner_text ON file_search_index(owner_id);
CREATE INDEX IF NOT EXISTS idx_file_embeddings_owner_type ON file_embeddings(owner_id, embedding_type);
