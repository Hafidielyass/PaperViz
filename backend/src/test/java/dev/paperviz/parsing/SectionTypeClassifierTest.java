package dev.paperviz.parsing;

import dev.paperviz.domain.model.Enums.SectionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SectionTypeClassifierTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "Abstract,                       ABSTRACT",
            "Introduction,                   INTRODUCTION",
            "1 Introduction,                 INTRODUCTION",
            "1. Introduction,                INTRODUCTION",
            "I. Introduction,                INTRODUCTION",
            "2 Background,                   RELATED_WORK",
            "Related Work,                   RELATED_WORK",
            "3 Model Architecture,           METHOD",
            "4 Experiments,                  EXPERIMENT",
            "5 Results,                      RESULTS",
            "Discussion,                     DISCUSSION",
            "Conclusion,                     CONCLUSION",
            "6. Conclusion,                  CONCLUSION",
            "References,                     REFERENCES",
            "Appendix A,                     APPENDIX",
            "Scaled Dot-Product Attention,   OTHER",
    })
    void classifiesConventionalHeadings(String heading, SectionType expected) {
        assertThat(SectionTypeClassifier.classify(heading.trim())).isEqualTo(expected);
    }

    /**
     * Regression: the numbering stripper used to run against the lowercased
     * heading with a character class of [0-9ivxlc], so it consumed the leading
     * letter of any word starting with a roman-numeral character.
     */
    @ParameterizedTest(name = "\"{0}\" keeps its leading letter")
    @CsvSource({
            "Introduction,       INTRODUCTION",
            "Conclusion,         CONCLUSION",
            "Limitations,        DISCUSSION",
            "Literature Review,  RELATED_WORK",
    })
    @DisplayName("headings beginning with a roman-numeral letter are not truncated")
    void doesNotEatLeadingRomanNumeralLetters(String heading, SectionType expected) {
        assertThat(SectionTypeClassifier.classify(heading.trim())).isEqualTo(expected);
    }

    /**
     * The heuristic only recognises conventional heading words. Descriptive
     * subsection titles fall through to OTHER by design — deciding what those
     * actually are is the LLM's job in the concept-extraction stage, not a
     * keyword list's.
     */
    @ParameterizedTest(name = "\"{0}\" is left as OTHER for the LLM to judge")
    @CsvSource({
            "3.1 Encoder and Decoder Stacks",
            "Scaled Dot-Product Attention",
            "Positional Encoding",
            "Why Self-Attention",
    })
    void leavesDescriptiveSubsectionsUnclassified(String heading) {
        assertThat(SectionTypeClassifier.classify(heading.trim())).isEqualTo(SectionType.OTHER);
    }

    @Test
    void handlesNullAndBlank() {
        assertThat(SectionTypeClassifier.classify(null)).isEqualTo(SectionType.OTHER);
        assertThat(SectionTypeClassifier.classify("   ")).isEqualTo(SectionType.OTHER);
    }

    @Test
    void stripsNumberingOnlyWhenASeparatorFollows() {
        // "IV. Results" is numbered; "Ivory tower metrics" is not.
        assertThat(SectionTypeClassifier.classify("IV. Results")).isEqualTo(SectionType.RESULTS);
        assertThat(SectionTypeClassifier.classify("Ivory tower metrics")).isEqualTo(SectionType.OTHER);
    }
}
