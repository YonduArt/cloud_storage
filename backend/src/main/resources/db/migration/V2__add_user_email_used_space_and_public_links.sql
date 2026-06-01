ALTER TABLE app_users ADD COLUMN IF NOT EXISTS email VARCHAR(180);
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS used_space BIGINT NOT NULL DEFAULT 0;

UPDATE app_users SET email = username WHERE email IS NULL;

ALTER TABLE app_users
    ALTER COLUMN email SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_app_users_email'
    ) THEN
        ALTER TABLE app_users ADD CONSTRAINT uk_app_users_email UNIQUE (email);
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS public_links (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(120) NOT NULL UNIQUE,
    file_id BIGINT NOT NULL REFERENCES stored_files(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_public_links_file_id ON public_links(file_id);
