ALTER TABLE public_links
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS idx_public_links_active_expires_at
    ON public_links(active, expires_at);
