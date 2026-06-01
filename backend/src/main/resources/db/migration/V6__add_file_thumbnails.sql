ALTER TABLE stored_files
    ADD COLUMN IF NOT EXISTS thumbnail_path VARCHAR(255);
