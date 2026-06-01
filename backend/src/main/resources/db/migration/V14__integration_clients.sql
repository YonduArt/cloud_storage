CREATE TABLE IF NOT EXISTS integration_clients (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    api_key_hash VARCHAR(128) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_integration_clients_owner ON integration_clients(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_integration_clients_enabled ON integration_clients(enabled);
