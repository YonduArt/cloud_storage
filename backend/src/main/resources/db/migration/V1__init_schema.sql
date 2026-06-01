CREATE TABLE IF NOT EXISTS app_users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_app_users_username ON app_users (username);

CREATE TABLE IF NOT EXISTS folders (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    owner_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    parent_id BIGINT NULL REFERENCES folders(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_folders_owner_parent ON folders(owner_id, parent_id);
CREATE INDEX IF NOT EXISTS idx_folders_owner_name ON folders(owner_id, name);
CREATE UNIQUE INDEX IF NOT EXISTS uq_folders_owner_parent_name ON folders(owner_id, COALESCE(parent_id, 0), lower(name));

CREATE TABLE IF NOT EXISTS stored_files (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    stored_path VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    owner_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    folder_id BIGINT NULL REFERENCES folders(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_stored_files_owner_folder ON stored_files(owner_id, folder_id);
CREATE INDEX IF NOT EXISTS idx_stored_files_owner_name ON stored_files(owner_id, name);
CREATE UNIQUE INDEX IF NOT EXISTS uq_files_owner_folder_name ON stored_files(owner_id, COALESCE(folder_id, 0), lower(name));
