-- Public observation projection; immutable selection manifests and hashes stay unchanged.
ALTER TABLE catalog_selection_snapshot ADD COLUMN provider_report jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(provider_report) = 'array');
UPDATE catalog_selection_snapshot s SET provider_report=r.report
FROM topic_catalog_refresh r WHERE r.published_selection=s.snapshot_id AND jsonb_typeof(r.report)='array';
