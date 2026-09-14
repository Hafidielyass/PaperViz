package dev.paperviz.ai;

import dev.paperviz.ai.AiModels.SegmentCopy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentCopyValidatorTest {

    private final SegmentCopyValidator validator = new SegmentCopyValidator();

    private static String words(int n) {
        return ("word ".repeat(n)).trim() + ".";
    }

    private static SegmentCopy copy(String body) {
        return new SegmentCopy("A Clear Title", body, null, null);
    }

    @Test
    void acceptsCopyInsideTheLimits() {
        SegmentCopy c = copy("Attention scores every word in a sentence against every other word. "
                + "Those scores become weights, and the weights blend the values into a single "
                + "vector for each position. Nothing is processed in sequence, so every word is "
                + "compared with every other at the same time.");

        assertThat(validator.validate(c).ok()).isTrue();
    }

    @Test
    void rejectsCopyOverTheWordLimit() {
        SegmentCopy c = copy(words(140));

        assertThat(validator.validate(c).describe())
                .contains("Body is 140 words; the limit is 90");
    }

    @Test
    void rejectsMoreThanTwoParagraphs() {
        SegmentCopy c = copy("First short paragraph here.\n\nSecond one here.\n\nA third one.");

        assertThat(validator.validate(c).describe()).contains("3 paragraphs");
    }

    /**
     * Regression from real output: a written part cited "Appendix B.1" and
     * another opened with "this paper", both of which the prompt forbids.
     */
    @ParameterizedTest(name = "rejects: {0}")
    @ValueSource(strings = {
            "BERT is evaluated on datasets described in Appendix B.1 with strong results shown there.",
            "This paper proposes a bidirectional encoder that reads the whole sentence at once always.",
            "The authors show that masking fifteen percent of tokens is enough for good pre-training.",
            "Results follow the setup of Vaswani et al. and improve on it across every task tested.",
            "Scores are reported in Table 2 and improve on every previous system by a wide margin.",
            "We propose a masked objective that lets the model read in both directions at once here.",
    })
    void rejectsPaperVoiceAndReferences(String body) {
        assertThat(validator.validate(copy(body)).ok()).isFalse();
    }

    @Test
    void allowsOrdinaryProseThatMerelyMentionsThoseWords() {
        // "section" without a number, and "paper" not as "the paper", are fine.
        SegmentCopy c = copy("Each section of the input is scored against all the others. "
                + "A paper trail of attention weights then shows which words mattered most for "
                + "a given prediction, which is useful when a model behaves in a way nobody "
                + "expected and someone has to work out why.");

        assertThat(validator.validate(c).ok()).isTrue();
    }

    @Test
    void trimsToTheLastCompleteSentenceInsideTheLimit() {
        String body = "One. " + words(50) + " " + words(50);

        String trimmed = validator.trimToLimit(body);

        assertThat(trimmed.split("\\s+").length).isLessThanOrEqualTo(SegmentCopyValidator.MAX_WORDS);
        assertThat(trimmed).startsWith("One.");
        assertThat(trimmed).endsWith(".");
    }

    @Test
    void leavesShortCopyUntouched() {
        String body = "Short and already fine.";
        assertThat(validator.trimToLimit(body)).isEqualTo(body);
    }

    @Test
    void dropsATakeawayThatOnlyRestatesTheBody() {
        String body = "BERT pre-trains on both left-to-right and right-to-left contexts, "
                + "which lets it understand relationships between words in both directions.";
        String takeaway = "BERT pre-trains on both left-to-right and right-to-left contexts, "
                + "enabling it to understand relationships between words in both directions.";

        assertThat(validator.takeawayAddsSomething(takeaway, body)).isFalse();
    }

    @Test
    void keepsATakeawayThatAddsSomething() {
        String body = "Masking hides a fraction of the input and asks the model to recover it.";
        String takeaway = "Fifteen percent turns out to be the sweet spot between signal and noise.";

        assertThat(validator.takeawayAddsSomething(takeaway, body)).isTrue();
    }

    @Test
    void treatsAMissingTakeawayAsNothingToKeep() {
        assertThat(validator.takeawayAddsSomething(null, "anything")).isFalse();
        assertThat(validator.takeawayAddsSomething("   ", "anything")).isFalse();
    }

    @Test
    void rejectsNullCopy() {
        assertThat(validator.validate(null).ok()).isFalse();
    }
}
