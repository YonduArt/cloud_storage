DROP INDEX IF EXISTS uq_files_owner_folder_name;

CREATE UNIQUE INDEX IF NOT EXISTS uq_files_owner_folder_name
    ON stored_files(owner_id, COALESCE(folder_id, 0), lower(name))
    WHERE deleted_at IS NULL;
