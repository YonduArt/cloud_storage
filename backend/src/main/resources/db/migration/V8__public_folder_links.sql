ALTER TABLE public_links
    ALTER COLUMN file_id DROP NOT NULL;

ALTER TABLE public_links
    ADD COLUMN IF NOT EXISTS folder_id BIGINT NULL REFERENCES folders(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_public_links_folder_id ON public_links(folder_id);
