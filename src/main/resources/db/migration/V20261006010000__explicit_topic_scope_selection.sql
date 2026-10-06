-- Preserve the original input while recording an explicit supported-field choice.
ALTER TABLE topic_request ADD COLUMN selected_slug varchar(120)
    CHECK (selected_slug IS NULL OR selected_slug ~ '^[a-z][a-z0-9-]{1,119}$');
