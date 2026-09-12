package dev.paperviz.parsing;

import dev.paperviz.domain.model.Enums.SectionType;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps a printed heading to a section type.
 *
 * Deliberately a heuristic, not an LLM call: this runs before any AI is
 * involved, and headings in the venues we target are highly conventional.
 * Anything unrecognised stays OTHER rather than being forced into a bucket.
 */
public final class SectionTypeClassifier {

    /**
     * Leading section numbering: "3", "3.1", "IV.", "2)".
     *
     * Two details matter here. Roman numerals are matched case-sensitively
     * against the original heading, and a separator is required after the
     * numeral. Without both, this pattern eats the first letter of any heading
     * that merely begins with a roman-numeral character — turning
     * "Introduction" into "ntroduction" and "Conclusion" into "onclusion",
     * which silently drops them into OTHER.
     */
    private static final Pattern LEADING_NUMBERING =
            Pattern.compile("^\\s*(?:\\d+(?:\\.\\d+)*|[IVXLC]+)\\s*[.)]?\\s+");

    private SectionTypeClassifier() {
    }

    public static SectionType classify(String heading) {
        if (heading == null || heading.isBlank()) {
            return SectionType.OTHER;
        }

        String h = LEADING_NUMBERING.matcher(heading).replaceFirst("")
                .toLowerCase(Locale.ROOT)
                .trim();

        if (h.startsWith("abstract")) {
            return SectionType.ABSTRACT;
        }
        if (h.startsWith("introduction") || h.startsWith("overview")) {
            return SectionType.INTRODUCTION;
        }
        if (h.contains("related work") || h.contains("prior work") || h.startsWith("background")
                || h.contains("literature review")) {
            return SectionType.RELATED_WORK;
        }
        if (h.startsWith("method") || h.startsWith("approach") || h.startsWith("model")
                || h.startsWith("architecture") || h.startsWith("proposed")
                || h.startsWith("preliminaries") || h.startsWith("formulation")
                || h.startsWith("our ")) {
            return SectionType.METHOD;
        }
        if (h.startsWith("experiment") || h.startsWith("evaluation") || h.startsWith("setup")
                || h.contains("experimental") || h.startsWith("training")
                || h.startsWith("implementation")) {
            return SectionType.EXPERIMENT;
        }
        if (h.startsWith("result") || h.startsWith("finding") || h.contains("ablation")) {
            return SectionType.RESULTS;
        }
        if (h.startsWith("discussion") || h.startsWith("limitation") || h.startsWith("analysis")) {
            return SectionType.DISCUSSION;
        }
        if (h.startsWith("conclusion") || h.startsWith("future work") || h.contains("concluding")) {
            return SectionType.CONCLUSION;
        }
        if (h.startsWith("reference") || h.startsWith("bibliograph")) {
            return SectionType.REFERENCES;
        }
        if (h.startsWith("appendix") || h.startsWith("supplement")) {
            return SectionType.APPENDIX;
        }
        return SectionType.OTHER;
    }
}
