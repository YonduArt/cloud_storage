ALTER TABLE stored_files
    ADD COLUMN IF NOT EXISTS extension VARCHAR(24),
    ADD COLUMN IF NOT EXISTS file_group VARCHAR(40) NOT NULL DEFAULT 'other',
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS purge_after TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS last_accessed_at TIMESTAMPTZ NULL;

UPDATE stored_files
SET extension = lower(split_part(name, '.', array_length(string_to_array(name, '.'), 1)))
WHERE extension IS NULL
  AND position('.' in name) > 0;

UPDATE stored_files
SET file_group =
    CASE
        WHEN lower(coalesce(extension, '')) IN ('jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'heic') THEN 'photo'
        WHEN lower(coalesce(extension, '')) IN ('mp4', 'mkv', 'mov', 'avi', 'webm') THEN 'video'
        WHEN lower(coalesce(extension, '')) IN ('mp3', 'wav', 'flac', 'aac', 'ogg', 'm4a') THEN 'audio'
        WHEN lower(coalesce(extension, '')) IN ('pdf') THEN 'pdf'
        WHEN lower(coalesce(extension, '')) IN ('doc', 'docx', 'txt', 'rtf', 'odt', 'xls', 'xlsx', 'ppt', 'pptx', 'csv') THEN 'document'
        WHEN lower(coalesce(extension, '')) IN ('zip', 'rar', '7z', 'tar', 'gz') THEN 'archive'
        ELSE 'other'
    END
WHERE file_group IS NULL
   OR file_group = '';

CREATE INDEX IF NOT EXISTS idx_stored_files_owner_deleted_at ON stored_files(owner_id, deleted_at);
CREATE INDEX IF NOT EXISTS idx_stored_files_owner_file_group ON stored_files(owner_id, file_group);
CREATE INDEX IF NOT EXISTS idx_stored_files_owner_uploaded_at ON stored_files(owner_id, uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_stored_files_owner_last_accessed_at ON stored_files(owner_id, last_accessed_at DESC);
