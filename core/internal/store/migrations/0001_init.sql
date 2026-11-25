-- Mirrors the vault's .ids records. Everything here can be rebuilt with Reindex.
CREATE TABLE groups (
    n            INTEGER PRIMARY KEY,
    id           TEXT NOT NULL UNIQUE,
    note_path    TEXT UNIQUE,          -- NULL until the Markdown exists
    note_version TEXT,
    title        TEXT NOT NULL,
    created_at   INTEGER NOT NULL,     -- unix seconds, when the index first saw it
    modified_at  INTEGER NOT NULL      -- unix seconds, the note file's modified time
);

CREATE TABLE files (
    group_n INTEGER NOT NULL REFERENCES groups(n) ON DELETE CASCADE,
    path    TEXT NOT NULL UNIQUE,
    version TEXT NOT NULL
);

CREATE TABLE tags (
    group_n INTEGER NOT NULL REFERENCES groups(n) ON DELETE CASCADE,
    tag     TEXT NOT NULL,
    PRIMARY KEY (group_n, tag)
);

-- rowid = groups.n
CREATE VIRTUAL TABLE notes_fts USING fts5(title, body, folder);
