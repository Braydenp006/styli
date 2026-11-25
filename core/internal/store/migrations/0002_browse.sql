-- Folder of each group (its note's folder, or its first file's), for browsing
-- and folder filters; plus indexes for the filters and sort orders.
-- Existing rows get '' until the next Reindex fills them in.
ALTER TABLE groups ADD COLUMN folder TEXT NOT NULL DEFAULT '';
CREATE INDEX groups_folder ON groups(folder);
CREATE INDEX groups_modified ON groups(modified_at);
CREATE INDEX tags_tag ON tags(tag);
