-- The reader is a short explainer, not a reproduction of the paper.
--
-- A paper parses into 25-30 verbatim sections, which is the right input for the
-- model but the wrong thing to put in front of a reader. A segment is one piece
-- of the finished explainer: a rewritten title, a couple of plain-language
-- paragraphs, an optional equation, a key takeaway, and one animation.
--
-- Sections stay as they are and remain the source material. Segments are what
-- the reader actually sees, and there are only a handful per paper.

CREATE TABLE segments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_id        UUID NOT NULL REFERENCES papers (id) ON DELETE CASCADE,
    ordinal         INT NOT NULL,

    title           TEXT NOT NULL,          -- rewritten, not the paper's heading
    body            TEXT NOT NULL,          -- plain-language explainer, 2-3 paragraphs
    key_takeaway    TEXT,
    latex           TEXT,                   -- one display equation, or NULL

    -- Which parsed sections this was written from. Lets the reader offer a
    -- "show source text" affordance and makes a bad rewrite traceable.
    source_ordinals INT[] NOT NULL DEFAULT '{}',

    storyboard_json JSONB,
    narration       TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (paper_id, ordinal)
);

CREATE INDEX ix_segments_paper ON segments (paper_id, ordinal);

-- Renders now hang off segments: one animation per segment, not one per concept.
-- Concepts remain as the intermediate shortlist the segment writer reads from.
ALTER TABLE renders
    ADD COLUMN segment_id UUID REFERENCES segments (id) ON DELETE CASCADE;

-- concept_id becomes optional, since a render is normally tied to a segment now.
ALTER TABLE renders
    ALTER COLUMN concept_id DROP NOT NULL;

-- The old uniqueness was one render per concept; it is now one per segment.
ALTER TABLE renders
    DROP CONSTRAINT IF EXISTS renders_concept_id_key;

CREATE UNIQUE INDEX ux_renders_segment ON renders (segment_id) WHERE segment_id IS NOT NULL;
CREATE UNIQUE INDEX ux_renders_concept ON renders (concept_id) WHERE concept_id IS NOT NULL;

-- A render must belong to exactly one of the two.
ALTER TABLE renders
    ADD CONSTRAINT renders_owner_check
        CHECK ((segment_id IS NULL) <> (concept_id IS NULL));

-- Papers gain a status for the writing step that sits between analysis and rendering.
ALTER TABLE papers
    DROP CONSTRAINT IF EXISTS papers_status_check;

ALTER TABLE papers
    ADD CONSTRAINT papers_status_check
        CHECK (status IN ('UPLOADED', 'PARSING', 'PARSED', 'ANALYZING', 'ANALYZED',
                          'WRITING', 'WRITTEN', 'RENDERING', 'READY', 'FAILED'));
