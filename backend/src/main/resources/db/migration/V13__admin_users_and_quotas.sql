ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS storage_quota_bytes BIGINT NOT NULL DEFAULT 1073741824;

UPDATE app_users
SET role = 'ROLE_ADMIN'
WHERE id = (
    SELECT MIN(id)
    FROM app_users
);
