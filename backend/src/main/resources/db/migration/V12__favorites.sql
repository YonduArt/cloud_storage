ALTER TABLE stored_files
    ADD COLUMN IF NOT EXISTS favorite BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE folders
    ADD COLUMN IF NOT EXISTS favorite BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_stored_files_owner_favorite
    ON stored_files(owner_id, favorite);

CREATE INDEX IF NOT EXISTS idx_folders_owner_favorite
    ON folders(owner_id, favorite);
