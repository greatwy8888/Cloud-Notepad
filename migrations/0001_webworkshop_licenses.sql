CREATE TABLE IF NOT EXISTS webworkshop_licenses (
  id TEXT PRIMARY KEY,
  serial_hash TEXT NOT NULL UNIQUE,
  serial_hint TEXT NOT NULL,
  product_id TEXT NOT NULL,
  build_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  used_at INTEGER,
  device_id TEXT,
  app_version TEXT,
  package_name TEXT,
  revoked_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_webworkshop_licenses_build
  ON webworkshop_licenses(product_id, build_id, created_at);

CREATE INDEX IF NOT EXISTS idx_webworkshop_licenses_status
  ON webworkshop_licenses(used_at, revoked_at, created_at);
