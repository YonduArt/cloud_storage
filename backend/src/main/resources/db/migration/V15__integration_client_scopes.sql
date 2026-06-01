ALTER TABLE integration_clients
    ADD COLUMN IF NOT EXISTS scopes VARCHAR(240) NOT NULL DEFAULT 'read,search,stats,upload,download,write';
