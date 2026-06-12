CREATE INDEX IF NOT EXISTS idx_store_location ON store USING GIST(location);
