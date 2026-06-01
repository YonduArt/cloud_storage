CREATE TABLE IF NOT EXISTS file_events (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    action VARCHAR(60) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT NULL,
    target_name VARCHAR(255) NOT NULL,
    details VARCHAR(500) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_file_events_owner_created_at ON file_events(owner_id, created_at DESC);
