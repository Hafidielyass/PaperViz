package dev.paperviz.ai;

import dev.paperviz.ai.AiModels.Storyboard;
import dev.paperviz.ai.AiModels.StoryboardBeat;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Checks a storyboard against the constraints the renderer and the narrator can
 * actually honour.
 *
 * This is the first validation gate. It runs before any Manim code exists,
 * because a storyboard that asks for twelve objects in four seconds produces a
 * bad animation no matter how good the generated code is — catching it here
 * saves a whole render cycle.
 */
@Component
public class StoryboardValidator {

    static final int MIN_BEATS = 4;
    static final int MAX_BEATS = 8;

    /**
     * The animation carries the explanation, so it needs room. The floor is
     * enforced again at render time, where measured narration decides the real
     * timings and short beats are padded out to reach it.
     */
    static final int MIN_TOTAL_SECONDS = 35;
    static final int MAX_TOTAL_SECONDS = 100;
    static final int MIN_BEAT_SECONDS = 3;
    static final int MAX_BEAT_SECONDS = 16;

    /** Speaking rate used to check narration fits its beat. */
    static final double WORDS_PER_SECOND = 2.5;

    /** Narration may exceed the nominal rate by this much before it is a problem. */
    static final double RATE_SLACK = 1.35;

    /** Above this token overlap, two beats are showing the same thing. */
    static final double VISUAL_SIMILARITY_LIMIT = 0.8;

    public Result validate(Storyboard sb) {
        List<String> problems = new ArrayList<>();

        if (sb == null) {
            return new Result(List.of("No storyboard was produced."));
        }
        if (sb.title() == null || sb.title().isBlank()) {
            problems.add("Missing title.");
        }

        List<StoryboardBeat> beats = sb.safeBeats();
        if (beats.size() < MIN_BEATS || beats.size() > MAX_BEATS) {
            problems.add("Expected %d-%d beats but got %d."
                    .formatted(MIN_BEATS, MAX_BEATS, beats.size()));
        }

        int summed = 0;
        for (int i = 0; i < beats.size(); i++) {
            StoryboardBeat beat = beats.get(i);
            int position = i + 1;

            if (beat.seconds() == null) {
                problems.add("Beat %d has no duration.".formatted(position));
                continue;
            }
            summed += beat.seconds();

            if (beat.seconds() < MIN_BEAT_SECONDS || beat.seconds() > MAX_BEAT_SECONDS) {
                problems.add("Beat %d is %ds; beats must be %d-%ds."
                        .formatted(position, beat.seconds(), MIN_BEAT_SECONDS, MAX_BEAT_SECONDS));
            }
            if (beat.visual() == null || beat.visual().isBlank()) {
                problems.add("Beat %d has no visual description.".formatted(position));
            }
            if (beat.narration() == null || beat.narration().isBlank()) {
                problems.add("Beat %d has no narration.".formatted(position));
                continue;
            }

            if (overflows(beat)) {
                problems.add("Beat %d narration is %d words but only ~%d fit in %ds."
                        .formatted(position, wordCount(beat.narration()),
                                speakableWords(beat.seconds()), beat.seconds()));
            }

            problems.addAll(validateScene(position, beat.effectiveScene()));
        }

        problems.addAll(findRepeatedVisuals(beats));
        problems.addAll(checkItIsAnAnimation(beats));
        problems.addAll(checkForVariety(beats));

        if (summed < MIN_TOTAL_SECONDS || summed > MAX_TOTAL_SECONDS) {
            problems.add("Total runtime %ds is outside %d-%ds."
                    .formatted(summed, MIN_TOTAL_SECONDS, MAX_TOTAL_SECONDS));
        }
        if (sb.totalSeconds() != null && Math.abs(sb.totalSeconds() - summed) > 1) {
            problems.add("Declared totalSeconds %d does not match the beats, which sum to %d."
                    .formatted(sb.totalSeconds(), summed));
        }

        return new Result(problems);
    }

    /**
     * Deterministically fixes what does not need another model roll.
     *
     * Only timing is repaired: a beat whose narration overruns is lengthened to
     * fit, and the declared total is recomputed. Anything about the content —
     * missing visuals, repeated beats, wrong beat count — is left alone,
     * because inventing that here would be making up the animation rather than
     * correcting it.
     *
     * @return a repaired copy, or the original when nothing needed changing
     */
    public Storyboard repairTiming(Storyboard sb) {
        if (sb == null || sb.safeBeats().isEmpty()) {
            return sb;
        }

        List<StoryboardBeat> repaired = new ArrayList<>();
        boolean changed = false;

        for (int i = 0; i < sb.safeBeats().size(); i++) {
            StoryboardBeat beat = sb.safeBeats().get(i);
            Integer seconds = beat.seconds();
            int order = beat.order() == null ? i + 1 : beat.order();

            if (beat.narration() != null && !beat.narration().isBlank()) {
                int needed = (int) Math.ceil(wordCount(beat.narration()) / WORDS_PER_SECOND);
                needed = Math.max(needed, MIN_BEAT_SECONDS);
                if (seconds == null || seconds < needed) {
                    seconds = Math.min(needed, MAX_BEAT_SECONDS);
                    changed = true;
                }
            }
            if (seconds == null) {
                seconds = MIN_BEAT_SECONDS;
                changed = true;
            }
            if (seconds > MAX_BEAT_SECONDS) {
                seconds = MAX_BEAT_SECONDS;
                changed = true;
            }

            repaired.add(new StoryboardBeat(
                    order, seconds, beat.visual(), beat.narration(), beat.latex(), beat.scene()));
        }

        int total = repaired.stream().mapToInt(StoryboardBeat::seconds).sum();
        if (!changed && sb.totalSeconds() != null && sb.totalSeconds() == total) {
            return sb;
        }
        return new Storyboard(sb.title(), sb.summary(), total, repaired);
    }

    /**
     * Checks the typed payload a beat will actually be rendered from.
     *
     * These are the failures that produce a chart nobody can read rather than a
     * crash, so they have to be caught here — the renderer will happily draw a
     * series with the wrong number of points.
     */
    private List<String> validateScene(int position, SceneSpec.Visual scene) {
        List<String> problems = new ArrayList<>();
        if (scene == null || scene.kind() == null) {
            return problems;
        }

        switch (scene.kind()) {
            case EQUATION -> {
                String latex = scene.latex();
                if (latex == null || latex.isBlank()) {
                    problems.add("Beat %d is an EQUATION beat with no latex.".formatted(position));
                } else {
                    problems.addAll(validateLatex(position, latex));
                }
            }
            case CHART -> problems.addAll(validateChart(position, scene.chart()));
            case DIAGRAM -> problems.addAll(validateDiagram(position, scene.diagram()));
            case TEXT -> {
                if (scene.text() == null || scene.text().isBlank()) {
                    problems.add("Beat %d is a TEXT beat with no text.".formatted(position));
                } else if (wordCount(scene.text()) > 25) {
                    problems.add("Beat %d puts %d words on screen; keep on-screen text under 25."
                            .formatted(position, wordCount(scene.text())));
                }
            }
            case FREEFORM -> {
                if (scene.description() == null || scene.description().isBlank()) {
                    problems.add("Beat %d has no visual to render.".formatted(position));
                }
            }
        }
        return problems;
    }

    /**
     * Cheap structural checks on LaTeX. A full parse belongs to the render
     * stage, where a real TeX run either compiles or does not — but unbalanced
     * braces are worth rejecting before we pay for that.
     */
    private List<String> validateLatex(int position, String latex) {
        List<String> problems = new ArrayList<>();

        int depth = 0;
        for (int i = 0; i < latex.length(); i++) {
            char c = latex.charAt(i);
            boolean escaped = i > 0 && latex.charAt(i - 1) == '\\';
            if (escaped) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth < 0) {
                    break;
                }
            }
        }
        if (depth != 0) {
            problems.add("Beat %d latex has unbalanced braces.".formatted(position));
        }

        // Delimiters are the renderer's job; leaving them in double-wraps the math.
        String trimmed = latex.trim();
        if (trimmed.startsWith("$") || trimmed.startsWith("\\[") || trimmed.startsWith("\\(")) {
            problems.add(("Beat %d latex includes math delimiters. Give the formula body only, "
                    + "with no $ or \\[ wrapping.").formatted(position));
        }
        return problems;
    }

    private List<String> validateChart(int position, SceneSpec.Chart chart) {
        List<String> problems = new ArrayList<>();
        if (chart == null) {
            problems.add("Beat %d is a CHART beat with no chart data.".formatted(position));
            return problems;
        }
        if (chart.kind() == null) {
            problems.add("Beat %d chart has no kind (BAR, LINE or SCATTER).".formatted(position));
        }

        List<String> categories = chart.safeCategories();
        List<SceneSpec.Series> series = chart.safeSeries();

        if (categories.isEmpty()) {
            problems.add("Beat %d chart has no categories.".formatted(position));
        } else if (categories.size() > 12) {
            problems.add("Beat %d chart has %d categories; keep it under 12 so labels stay legible."
                    .formatted(position, categories.size()));
        }
        if (series.isEmpty()) {
            problems.add("Beat %d chart has no series.".formatted(position));
        } else if (series.size() > 4) {
            problems.add("Beat %d chart has %d series; keep it to 4 or fewer."
                    .formatted(position, series.size()));
        }

        // Series named after the categories means the two axes have been mixed
        // up: the thing being compared has been used as both the x-axis and the
        // legend. Lengths still line up, so nothing else here would catch it.
        Set<String> categoryKeys = categories.stream()
                .filter(java.util.Objects::nonNull)
                .map(c -> c.toLowerCase(Locale.ROOT).trim())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        long clashing = series.stream()
                .map(SceneSpec.Series::name)
                .filter(java.util.Objects::nonNull)
                .map(n -> n.toLowerCase(Locale.ROOT).trim())
                .filter(categoryKeys::contains)
                .count();
        if (clashing > 0 && !categoryKeys.isEmpty()) {
            problems.add(("Beat %d chart uses the same labels for its categories and its series, "
                    + "so the axes are mixed up. Categories are what sits along the x-axis; "
                    + "series are the quantities being compared across them.")
                    .formatted(position));
        }

        for (SceneSpec.Series s : series) {
            String name = s.name() == null ? "(unnamed)" : s.name();
            if (s.safeValues().isEmpty()) {
                problems.add("Beat %d chart series '%s' has no values.".formatted(position, name));
            } else if (!categories.isEmpty() && s.safeValues().size() != categories.size()) {
                problems.add(("Beat %d chart series '%s' has %d values but there are %d "
                        + "categories; they must match one to one.")
                        .formatted(position, name, s.safeValues().size(), categories.size()));
            }
            if (s.safeValues().stream().anyMatch(v -> v == null || v.isNaN() || v.isInfinite())) {
                problems.add("Beat %d chart series '%s' contains a value that is not a number."
                        .formatted(position, name));
            }
        }
        return problems;
    }

    private List<String> validateDiagram(int position, SceneSpec.Diagram diagram) {
        List<String> problems = new ArrayList<>();
        if (diagram == null) {
            problems.add("Beat %d is a DIAGRAM beat with no diagram.".formatted(position));
            return problems;
        }

        List<SceneSpec.Node> nodes = diagram.safeNodes();
        if (nodes.isEmpty()) {
            problems.add("Beat %d diagram has no nodes.".formatted(position));
            return problems;
        }
        if (nodes.size() > 8) {
            problems.add("Beat %d diagram has %d boxes; more than 8 will not fit the frame."
                    .formatted(position, nodes.size()));
        }

        Set<String> ids = new LinkedHashSet<>();
        for (SceneSpec.Node node : nodes) {
            if (node.id() == null || node.id().isBlank()) {
                problems.add("Beat %d diagram has a node with no id.".formatted(position));
            } else if (!ids.add(node.id())) {
                problems.add("Beat %d diagram reuses node id '%s'.".formatted(position, node.id()));
            }
        }
        for (SceneSpec.Edge edge : diagram.safeEdges()) {
            if (edge.from() == null || !ids.contains(edge.from())) {
                problems.add("Beat %d diagram has an arrow from unknown node '%s'."
                        .formatted(position, edge.from()));
            }
            if (edge.to() == null || !ids.contains(edge.to())) {
                problems.add("Beat %d diagram has an arrow to unknown node '%s'."
                        .formatted(position, edge.to()));
            }
        }
        return problems;
    }

    /**
     * Rejects a storyboard that shows the same shape over and over.
     *
     * The equality check catches two beats with identical payloads, but the
     * real failure looks different: five DIAGRAM beats, each two boxes and an
     * arrow, with only the labels changed. Those are different objects and
     * identical pictures. Comparing structure rather than content is what
     * catches it.
     */
    private List<String> checkForVariety(List<StoryboardBeat> beats) {
        if (beats.size() < 3) {
            return List.of();
        }

        // FREEFORM is excluded on purpose: its shape is opaque, so two of them
        // are indistinguishable here even when they draw different things. The
        // text-similarity check already covers that case, and counting them
        // would mean rejecting a storyboard twice for one suspicion.
        Map<String, Integer> shapes = new java.util.LinkedHashMap<>();
        int typed = 0;
        for (StoryboardBeat beat : beats) {
            SceneSpec.Visual scene = beat.effectiveScene();
            if (scene.kind() == SceneSpec.VisualKind.FREEFORM) {
                continue;
            }
            typed++;
            shapes.merge(shapeOf(scene), 1, Integer::sum);
        }
        if (typed < 3) {
            return List.of();
        }

        List<String> problems = new ArrayList<>();
        int limit = Math.max(2, (typed + 1) / 2);
        for (Map.Entry<String, Integer> entry : shapes.entrySet()) {
            if (entry.getValue() > limit) {
                problems.add(("%d of %d drawn beats show the same thing (%s), so the part barely "
                        + "changes. Vary what each beat shows — a structure, then the formula "
                        + "behind it, then the numbers it produces.")
                        .formatted(entry.getValue(), typed, entry.getKey()));
            }
        }
        return problems;
    }

    /**
     * A beat's shape: its kind plus the size of what it draws, ignoring labels.
     *
     * Two diagrams of two boxes and one arrow share a shape however they are
     * captioned, which is exactly the case worth rejecting.
     */
    private String shapeOf(SceneSpec.Visual scene) {
        if (scene == null || scene.kind() == null) {
            return "nothing";
        }
        return switch (scene.kind()) {
            case DIAGRAM -> {
                SceneSpec.Diagram d = scene.diagram();
                if (d == null) {
                    yield "an empty diagram";
                }
                int groups = d.safeGroups().size();
                int boxes = d.safeNodes().size()
                        + d.safeGroups().stream().mapToInt(g -> g.safeNodes().size()).sum();
                yield "a diagram of %d group(s), %d box(es), %d arrow(s)"
                        .formatted(groups, boxes, d.safeEdges().size());
            }
            case CHART -> {
                SceneSpec.Chart c = scene.chart();
                yield c == null ? "an empty chart"
                        : "a %s chart of %d categories".formatted(
                                c.kind() == null ? "bar" : c.kind().name().toLowerCase(Locale.ROOT),
                                c.safeCategories().size());
            }
            case EQUATION -> "an equation";
            case TEXT -> "a text caption";
            case FREEFORM -> "a freeform visual";
        };
    }

    /**
     * A storyboard made entirely of text captions is a slideshow, not an
     * animation — and the reader already has the prose next to the video, so
     * captions add nothing. At least one beat has to show a real visual.
     */
    private List<String> checkItIsAnAnimation(List<StoryboardBeat> beats) {
        if (beats.isEmpty()) {
            return List.of();
        }
        boolean anyVisual = beats.stream()
                .map(StoryboardBeat::effectiveScene)
                .anyMatch(scene -> scene.kind() == SceneSpec.VisualKind.EQUATION
                        || scene.kind() == SceneSpec.VisualKind.CHART
                        || scene.kind() == SceneSpec.VisualKind.DIAGRAM
                        || scene.kind() == SceneSpec.VisualKind.FREEFORM);
        if (anyVisual) {
            return List.of();
        }
        return List.of("Every beat is a text caption, which is a slideshow rather than an "
                + "animation. At least one beat must be an EQUATION, CHART or DIAGRAM.");
    }

    /**
     * Beats that describe the same picture mean nothing moves. The model tends
     * to do this by restating the previous beat's scene verbatim.
     */
    private List<String> findRepeatedVisuals(List<StoryboardBeat> beats) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < beats.size(); i++) {
            for (int j = i + 1; j < beats.size(); j++) {
                StoryboardBeat first = beats.get(i);
                StoryboardBeat second = beats.get(j);

                // An identical typed payload renders as an identical frame no
                // matter how differently the prose describes it — a chart drawn
                // three times from the same numbers is three still images.
                SceneSpec.Visual sceneA = first.effectiveScene();
                SceneSpec.Visual sceneB = second.effectiveScene();
                if (sceneA.kind() != SceneSpec.VisualKind.FREEFORM && sceneA.equals(sceneB)) {
                    problems.add(("Beats %d and %d render exactly the same %s, so nothing would "
                            + "change on screen. Either vary what is shown or merge them.")
                            .formatted(i + 1, j + 1, sceneA.kind()));
                    continue;
                }

                String a = first.visual();
                String b = second.visual();
                if (a == null || b == null || a.isBlank() || b.isBlank()) {
                    continue;
                }
                if (similarity(a, b) >= VISUAL_SIMILARITY_LIMIT) {
                    problems.add(("Beats %d and %d describe the same picture, so nothing would "
                            + "visibly change between them. Each beat must show a new state.")
                            .formatted(i + 1, j + 1));
                }
            }
        }
        return problems;
    }

    /** Jaccard overlap of the word sets — crude, but it catches restated scenes. */
    private double similarity(String a, String b) {
        Set<String> setA = tokens(a);
        Set<String> setB = tokens(b);
        if (setA.isEmpty() || setB.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new LinkedHashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new LinkedHashSet<>(setA);
        union.addAll(setB);
        return (double) intersection.size() / union.size();
    }

    private Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 2) {
                result.add(token);
            }
        }
        return result;
    }

    private boolean overflows(StoryboardBeat beat) {
        if (beat.narration() == null || beat.seconds() == null) {
            return false;
        }
        return wordCount(beat.narration()) > speakableWords(beat.seconds()) * RATE_SLACK;
    }

    private int wordCount(String text) {
        return text.trim().split("\\s+").length;
    }

    private int speakableWords(int seconds) {
        return (int) Math.ceil(seconds * WORDS_PER_SECOND);
    }

    public record Result(List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }

        public String describe() {
            return String.join(" ", problems);
        }
    }
}
