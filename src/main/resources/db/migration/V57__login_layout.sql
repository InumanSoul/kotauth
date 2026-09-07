ALTER TABLE workspace_theme
    ADD COLUMN login_layout TEXT NOT NULL DEFAULT 'CENTERED',
    ADD COLUMN login_background_url VARCHAR(500),
    ADD COLUMN login_tagline VARCHAR(200);
