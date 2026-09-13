package dev.paperviz.ai;

import dev.paperviz.ai.AiModels.Storyboard;
import dev.paperviz.ai.AiModels.StoryboardBeat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryboardValidatorTest {

    private final StoryboardValidator validator = new StoryboardValidator();

    private static StoryboardBeat beat(int order, int seconds, String visual, String narration) {
        return new StoryboardBeat(order, seconds, visual, narration, null);
    }

    /** 2.5 words per second, so a 6s beat comfortably fits 12 words. */
    private static String words(int n) {
        return "word ".repeat(n).trim();
    }

    private static Storyboard board(List<StoryboardBeat> beats) {
        int total = beats.stream().mapToInt(StoryboardBeat::seconds).sum();
        return new Storyboard("A Title", "A summary", total, beats);
    }

    @Test
    void acceptsAWellFormedStoryboard() {
        Storyboard sb = board(List.of(
                beat(1, 6, "A blue square sits alone at the centre", words(12)),
                beat(2, 6, "An arrow grows rightward toward a red circle", words(12)),
                beat(3, 6, "The circle splits into three stacked bars", words(12))));

        assertThat(validator.validate(sb).ok()).isTrue();
    }

    @Test
    void rejectsNarrationThatCannotBeSpokenInTime() {
        Storyboard sb = board(List.of(
                beat(1, 5, "A square", words(40)),
                beat(2, 5, "A circle appears beside it", words(10))));

        StoryboardValidator.Result result = validator.validate(sb);
        assertThat(result.ok()).isFalse();
        assertThat(result.describe()).contains("Beat 1 narration is 40 words");
    }

    @Test
    void rejectsBeatsThatShowTheSamePicture() {
        String same = "The sentence stays on screen and a random token is highlighted";
        Storyboard sb = board(List.of(
                beat(1, 6, same, words(12)),
                beat(2, 6, same, words(12)),
                beat(3, 6, "Three coloured bars rise from the baseline", words(12))));

        StoryboardValidator.Result result = validator.validate(sb);
        assertThat(result.ok()).isFalse();
        assertThat(result.describe()).contains("Beats 1 and 2 describe the same picture");
    }

    @Test
    void rejectsWhenDeclaredTotalDisagreesWithTheBeats() {
        Storyboard sb = new Storyboard("T", "S", 99, List.of(
                beat(1, 6, "A square", words(12)),
                beat(2, 6, "A circle", words(12))));

        assertThat(validator.validate(sb).describe())
                .contains("Declared totalSeconds 99 does not match");
    }

    @Test
    void repairStretchesAnOverrunningBeatInsteadOfDiscardingIt() {
        // 18 words needs ~8s; the model asked for 5s.
        Storyboard sb = board(List.of(
                beat(1, 5, "A square appears", words(18)),
                beat(2, 6, "An arrow points to a circle", words(12))));

        assertThat(validator.validate(sb).ok()).isFalse();

        Storyboard repaired = validator.repairTiming(sb);

        assertThat(repaired).isNotSameAs(sb);
        assertThat(repaired.beats().get(0).seconds()).isGreaterThanOrEqualTo(8);
        assertThat(validator.validate(repaired).ok()).isTrue();
    }

    @Test
    void repairRecomputesTheDeclaredTotal() {
        Storyboard sb = new Storyboard("T", "S", 11, List.of(
                beat(1, 5, "A square", words(18)),
                beat(2, 6, "A circle", words(12))));

        Storyboard repaired = validator.repairTiming(sb);

        int summed = repaired.beats().stream().mapToInt(StoryboardBeat::seconds).sum();
        assertThat(repaired.totalSeconds()).isEqualTo(summed);
    }

    @Test
    void repairLeavesAValidBoardAlone() {
        Storyboard sb = board(List.of(
                beat(1, 6, "A square", words(12)),
                beat(2, 6, "A circle", words(12))));

        assertThat(validator.repairTiming(sb)).isSameAs(sb);
    }

    @Test
    void repairDoesNotInventContent() {
        // Missing visuals are a content problem; repair must not paper over them.
        Storyboard sb = board(List.of(
                beat(1, 6, null, words(12)),
                beat(2, 6, "A circle", words(12))));

        Storyboard repaired = validator.repairTiming(sb);

        assertThat(repaired.beats().get(0).visual()).isNull();
        assertThat(validator.validate(repaired).ok()).isFalse();
    }

    @Test
    void rejectsANullStoryboard() {
        assertThat(validator.validate(null).ok()).isFalse();
    }
}
