package dev.paperviz.domain.model;

/** Enum values are persisted as strings and must match the CHECK constraints in V1__init.sql. */
public final class Enums {

    private Enums() {
    }

    public enum SourceType {
        UPLOAD,
        OPEN_ACCESS_URL
    }

    public enum PaperStatus {
        UPLOADED, PARSING, PARSED, ANALYZING, ANALYZED, RENDERING, READY, FAILED
    }

    public enum SectionType {
        ABSTRACT, INTRODUCTION, RELATED_WORK, METHOD, EXPERIMENT, RESULTS,
        DISCUSSION, CONCLUSION, REFERENCES, APPENDIX, OTHER
    }

    public enum ConceptType {
        EQUATION, ARCHITECTURE, ALGORITHM, RESULT, INTUITION, OTHER
    }

    public enum RenderStatus {
        PENDING, GENERATING, VALIDATING, RENDERING, READY, FAILED
    }

    public enum JobType {
        PARSE, ANALYZE, STORYBOARD, RENDER
    }

    public enum JobStatus {
        PENDING, RUNNING, SUCCEEDED, FAILED
    }
}
