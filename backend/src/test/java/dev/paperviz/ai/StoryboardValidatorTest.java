package dev.paperviz.ai;

import dev.paperviz.ai.AiModels.Storyboard;
import dev.paperviz.ai.AiModels.StoryboardBeat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryboardValidatorTest {

    private final StoryboardValidator validator = new StoryboardValidator();

    private static StoryboardBeat beat(int order, int seconds, String visual, String narration) {
        return new StoryboardBeat(order, seconds, visual, narration, null, null);
    }

    /** 2.5 words per second, so a 6s beat comfortably fits 12 words. */
    private static String words(int n) {
        return "word ".repeat(n).trim();
    }

    /**
     * Wraps the beats a test cares about, padding to the validator's minimum
     * beat count and runtime with visually distinct filler so a test about one
     * rule is not also tripping the length floor.
     */
    private static Storyboard board(List<StoryboardBeat> beats) {
        List<StoryboardBeat> all = new java.util.ArrayList<>(beats);
        String[] fillers = {
            "A blue square drifts to the upper left",
            "A green triangle rotates once about its centre",
            "A dotted line sweeps from the baseline upward",
            "Two circles orbit and then settle side by side",
            "A grid fades up behind everything already on screen",
            "A single dot travels the width of the frame",
        };
        int f = 0;
        while (all.size() < 4 || all.stream().mapToInt(StoryboardBeat::seconds).sum() < 35) {
            all.add(beat(all.size() + 1, 10, fillers[f % fillers.length], words(20)));
            f++;
        }
        int total = all.stream().mapToInt(StoryboardBeat::seconds).sum();
        return new Storyboard("A Title", "A summary", total, all);
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
        Storyboard sb = new Storyboard("T", "S", 999, List.of(
                beat(1, 10, "A square slides in", words(20)),
                beat(2, 10, "A circle grows beside it", words(20)),
                beat(3, 10, "An arrow links the two", words(20)),
                beat(4, 10, "Three bars rise from the baseline", words(20))));

        assertThat(validator.validate(sb).describe())
                .contains("Declared totalSeconds 999 does not match");
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
                beat(2, 6, "A circle", words(12)),
                beat(3, 10, "An arrow", words(20)),
                beat(4, 10, "Three bars", words(20))));

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

    // --- typed scenes: charts, equations, diagrams ---------------------------

    private static StoryboardBeat scened(int order, int seconds, String visual, SceneSpec.Visual scene) {
        return new StoryboardBeat(order, seconds, visual, words(10), null, scene);
    }

    private static SceneSpec.Visual chart(SceneSpec.Chart c) {
        return new SceneSpec.Visual(SceneSpec.VisualKind.CHART, null, null, c, null, null);
    }

    private static SceneSpec.Visual equation(String latex) {
        return new SceneSpec.Visual(SceneSpec.VisualKind.EQUATION, null, latex, null, null, null);
    }

    @Test
    void acceptsAWellFormedChart() {
        SceneSpec.Chart c = new SceneSpec.Chart(
                SceneSpec.ChartKind.BAR, "Model", "BLEU",
                List.of("GNMT", "ConvS2S", "Transformer"),
                List.of(new SceneSpec.Series("EN-DE", List.of(24.6, 25.2, 28.4))));

        Storyboard sb = board(List.of(
                scened(1, 6, "A bar chart of BLEU scores", chart(c)),
                beat(2, 6, "The Transformer bar is highlighted", words(12))));

        assertThat(validator.validate(sb).ok()).isTrue();
    }

    @Test
    void rejectsAChartWhoseSeriesDoesNotMatchItsCategories() {
        // Three categories, two values — the renderer would silently draw nonsense.
        SceneSpec.Chart c = new SceneSpec.Chart(
                SceneSpec.ChartKind.BAR, "Model", "BLEU",
                List.of("GNMT", "ConvS2S", "Transformer"),
                List.of(new SceneSpec.Series("EN-DE", List.of(24.6, 25.2))));

        Storyboard sb = board(List.of(
                scened(1, 6, "A bar chart", chart(c)),
                beat(2, 6, "A circle appears", words(12))));

        assertThat(validator.validate(sb).describe())
                .contains("has 2 values but there are 3 categories");
    }

    @Test
    void rejectsAChartBeatWithNoData() {
        Storyboard sb = board(List.of(
                scened(1, 6, "A chart of results", chart(null)),
                beat(2, 6, "A square appears", words(12))));

        assertThat(validator.validate(sb).describe()).contains("CHART beat with no chart data");
    }

    @Test
    void rejectsLatexWithUnbalancedBraces() {
        Storyboard sb = board(List.of(
                scened(1, 6, "The attention formula", equation("\\frac{QK^T}{\\sqrt{d_k}")),
                beat(2, 6, "A square appears", words(12))));

        assertThat(validator.validate(sb).describe()).contains("unbalanced braces");
    }

    @Test
    void rejectsLatexThatBringsItsOwnMathDelimiters() {
        Storyboard sb = board(List.of(
                scened(1, 6, "The formula", equation("$E = mc^2$")),
                beat(2, 6, "A square appears", words(12))));

        assertThat(validator.validate(sb).describe()).contains("includes math delimiters");
    }

    @Test
    void acceptsCleanLatex() {
        Storyboard sb = board(List.of(
                scened(1, 6, "The attention formula", equation("\\frac{QK^T}{\\sqrt{d_k}}")),
                beat(2, 6, "A square appears", words(12))));

        assertThat(validator.validate(sb).ok()).isTrue();
    }

    @Test
    void promotesABareLatexFieldToAnEquationScene() {
        StoryboardBeat bare = new StoryboardBeat(1, 6, "A formula", words(10), "E = mc^2", null);

        assertThat(bare.effectiveScene().kind()).isEqualTo(SceneSpec.VisualKind.EQUATION);
        assertThat(bare.effectiveScene().latex()).isEqualTo("E = mc^2");
    }

    @Test
    void fallsBackToFreeformWhenNoSceneIsGiven() {
        StoryboardBeat plain = new StoryboardBeat(1, 6, "Two boxes side by side", words(10), null, null);

        assertThat(plain.effectiveScene().kind()).isEqualTo(SceneSpec.VisualKind.FREEFORM);
        assertThat(plain.effectiveScene().description()).isEqualTo("Two boxes side by side");
    }

    /**
     * Regression from real output: the model produced categories and series with
     * the same labels. Lengths matched, so every other check passed.
     */
    @Test
    void rejectsAChartThatUsesTheSameLabelsForCategoriesAndSeries() {
        SceneSpec.Chart c = new SceneSpec.Chart(
                SceneSpec.ChartKind.BAR, "Model", "Score",
                List.of("BERT BASE", "BERT LARGE"),
                List.of(new SceneSpec.Series("BERT BASE", List.of(80.5, 72.8)),
                        new SceneSpec.Series("BERT LARGE", List.of(85.5, 80.5))));

        Storyboard sb = board(List.of(
                scened(1, 6, "A grouped bar chart", chart(c)),
                beat(2, 6, "The taller bar is highlighted", words(12))));

        assertThat(validator.validate(sb).describe())
                .contains("same labels for its categories and its series");
    }

    @Test
    void acceptsAChartWhoseSeriesAreDistinctFromItsCategories() {
        SceneSpec.Chart c = new SceneSpec.Chart(
                SceneSpec.ChartKind.BAR, "Task", "Score",
                List.of("MNLI", "QQP"),
                List.of(new SceneSpec.Series("BERT BASE", List.of(84.6, 71.2)),
                        new SceneSpec.Series("BERT LARGE", List.of(86.7, 72.1))));

        Storyboard sb = board(List.of(
                scened(1, 6, "A grouped bar chart", chart(c)),
                beat(2, 6, "The taller bars are highlighted", words(12))));

        assertThat(validator.validate(sb).ok()).isTrue();
    }

    /**
     * Regression: a run produced five TEXT beats, which is a slideshow.
     *
     * Built without the padding helper on purpose — its filler beats are
     * FREEFORM, which would satisfy the very rule under test.
     */
    @Test
    void rejectsAStoryboardThatIsNothingButTextCaptions() {
        String[] captions = {
            "Fine-tuning BERT", "Three epochs over the data",
            "A learning rate of five e minus five", "Batches of thirty-two",
        };
        List<StoryboardBeat> beats = new java.util.ArrayList<>();
        for (int i = 0; i < captions.length; i++) {
            beats.add(scened(i + 1, 10, "Caption " + (i + 1) + " on screen alone",
                    new SceneSpec.Visual(SceneSpec.VisualKind.TEXT,
                            captions[i], null, null, null, null)));
        }
        Storyboard sb = new Storyboard("T", "S", 40, beats);

        assertThat(validator.validate(sb).describe()).contains("slideshow rather than an animation");
    }

    @Test
    void acceptsTextBeatsAlongsideARealVisual() {
        SceneSpec.Visual text = new SceneSpec.Visual(
                SceneSpec.VisualKind.TEXT, "Scaled dot-product attention", null, null, null, null);

        Storyboard sb = board(List.of(
                scened(1, 6, "A caption appears", text),
                scened(2, 6, "The formula fades in", equation("\\frac{QK^T}{\\sqrt{d_k}}"))));

        assertThat(validator.validate(sb).ok()).isTrue();
    }

    /**
     * Regression: a run drew the same bar chart in three consecutive beats. The
     * prose differed each time, so the text-similarity check let it through.
     */
    @Test
    void rejectsBeatsThatRenderTheIdenticalChart() {
        SceneSpec.Chart c = new SceneSpec.Chart(
                SceneSpec.ChartKind.BAR, "Model", "Accuracy",
                List.of("BERT BASE", "BERT LARGE"),
                List.of(new SceneSpec.Series("Improvement", List.of(4.5, 7.0))));

        Storyboard sb = board(List.of(
                scened(1, 6, "A bar chart of the improvement appears", chart(c)),
                scened(2, 6, "The chart is examined more closely", chart(c))));

        assertThat(validator.validate(sb).describe()).contains("render exactly the same CHART");
    }

    @Test
    void allowsTwoChartsThatShowDifferentData() {
        SceneSpec.Chart first = new SceneSpec.Chart(
                SceneSpec.ChartKind.BAR, "Task", "Score", List.of("MNLI", "QQP"),
                List.of(new SceneSpec.Series("BERT BASE", List.of(84.6, 71.2))));
        SceneSpec.Chart second = new SceneSpec.Chart(
                SceneSpec.ChartKind.BAR, "Task", "Score", List.of("MNLI", "QQP"),
                List.of(new SceneSpec.Series("BERT LARGE", List.of(86.7, 72.1))));

        Storyboard sb = board(List.of(
                scened(1, 6, "The base model's scores appear", chart(first)),
                scened(2, 6, "The large model's scores overlay them", chart(second))));

        assertThat(validator.validate(sb).ok()).isTrue();
    }

    @Test
    void doesNotFlagTwoFreeformBeatsAsIdentical() {
        // FREEFORM payloads derive from the prose, which the text check already covers.
        Storyboard sb = board(List.of(
                beat(1, 6, "A square slides in from the left", words(12)),
                beat(2, 6, "Three bars rise from the baseline", words(12))));

        assertThat(validator.validate(sb).ok()).isTrue();
    }

    @Test
    void rejectsADiagramArrowPointingAtANodeThatDoesNotExist() {
        SceneSpec.Diagram d = new SceneSpec.Diagram(
                List.of(new SceneSpec.Node("enc", "Encoder", null)),
                List.of(new SceneSpec.Edge("enc", "dec", "context")),
                null);

        Storyboard sb = board(List.of(
                scened(1, 6, "Encoder and decoder",
                        new SceneSpec.Visual(SceneSpec.VisualKind.DIAGRAM, null, null, null, d, null)),
                beat(2, 6, "A square appears", words(12))));

        // "id" not "node": an edge may now point at a group as well as a box.
        assertThat(validator.validate(sb).describe()).contains("arrow to unknown id 'dec'");
    }
}
