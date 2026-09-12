-- PaperViz initial schema.
-- v1 runs single-user, but every paper still carries an owner_id so turning on
-- real auth later is a controller change, not a migration.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      TEXT NOT NULL UNIQUE,
    display_name  TEXT,
    password_hash TEXT,                       -- NULL for the seeded local user
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO users (id, username, display_name)
VALUES ('00000000-0000-0000-0000-000000000001', 'local', 'Local User');

CREATE TABLE papers (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id          UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source_type       TEXT NOT NULL CHECK (source_type IN ('UPLOAD', 'OPEN_ACCESS_URL')),
    source_url        TEXT,                   -- only set for OPEN_ACCESS_URL
    open_access_proof TEXT,                   -- allowlist hit or Unpaywall evidence
    original_filename TEXT,
    content_sha256    VARCHAR(64) NOT NULL,   -- cache key: same bytes => reuse everything
    title             TEXT,
    authors           TEXT,
    status            TEXT NOT NULL DEFAULT 'UPLOADED'
                      CHECK (status IN ('UPLOADED', 'PARSING', 'PARSED', 'ANALYZING',
                                        'ANALYZED', 'RENDERING', 'READY', 'FAILED')),
    status_detail     TEXT,
    storage_path      TEXT NOT NULL,          -- path under MEDIA_ROOT, never a public URL
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Re-uploading the same PDF resolves to the existing paper instead of reprocessing.
CREATE UNIQUE INDEX ux_papers_owner_hash ON papers (owner_id, content_sha256);
CREATE INDEX ix_papers_owner_created ON papers (owner_id, created_at DESC);

CREATE TABLE sections (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_id     UUID NOT NULL REFERENCES papers (id) ON DELETE CASCADE,
    ordinal      INT NOT NULL,
    section_type TEXT NOT NULL DEFAULT 'OTHER'
                 CHECK (section_type IN ('ABSTRACT', 'INTRODUCTION', 'RELATED_WORK', 'METHOD',
                                         'EXPERIMENT', 'RESULTS', 'DISCUSSION', 'CONCLUSION',
                                         'REFERENCES', 'APPENDIX', 'OTHER')),
    heading      TEXT,
    raw_text     TEXT NOT NULL,
    latex        TEXT,                        -- inline/display math preserved when available
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (paper_id, ordinal)
);

CREATE TABLE concepts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id      UUID NOT NULL REFERENCES sections (id) ON DELETE CASCADE,
    ordinal         INT NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT,
    concept_type    TEXT NOT NULL DEFAULT 'OTHER'
                    CHECK (concept_type IN ('EQUATION', 'ARCHITECTURE', 'ALGORITHM',
                                            'RESULT', 'INTUITION', 'OTHER')),
    animate         BOOLEAN NOT NULL DEFAULT FALSE,   -- false => plain text, skip the renderer
    storyboard_json JSONB,
    narration       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (section_id, ordinal)
);

CREATE TABLE renders (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    concept_id       UUID NOT NULL UNIQUE REFERENCES concepts (id) ON DELETE CASCADE,
    status           TEXT NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'GENERATING', 'VALIDATING',
                                       'RENDERING', 'READY', 'FAILED')),
    manim_code       TEXT,
    video_path       TEXT,
    narration_path   TEXT,
    duration_seconds NUMERIC(8, 2),
    retry_count      INT NOT NULL DEFAULT 0,
    last_error       TEXT,
    cache_key        VARCHAR(64),             -- sha256(paper hash + storyboard) => instant reuse
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_renders_cache_key ON renders (cache_key);
CREATE INDEX ix_renders_status ON renders (status);

-- Postgres-backed job queue. Deliberately not Kafka/Rabbit at v1 scale.
CREATE TABLE jobs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_id    UUID REFERENCES papers (id) ON DELETE CASCADE,
    job_type    TEXT NOT NULL
                CHECK (job_type IN ('PARSE', 'ANALYZE', 'STORYBOARD', 'RENDER')),
    status      TEXT NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    payload     JSONB,
    attempts    INT NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE INDEX ix_jobs_status_created ON jobs (status, created_at);
CREATE INDEX ix_jobs_paper ON jobs (paper_id);
