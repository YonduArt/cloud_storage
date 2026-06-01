DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'app_users' AND column_name = 'password'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'app_users' AND column_name = 'password_hash'
    ) THEN
        ALTER TABLE app_users RENAME COLUMN password TO password_hash;
    END IF;
END
$$;
