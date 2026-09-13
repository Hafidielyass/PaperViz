package dev.paperviz.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * What a beat puts on screen, as data rather than as code.
 *
 * The model chooses a kind and fills in the matching payload; the render
 * service turns that into Manim deterministically. This is the difference
 * between an animation that renders and one that does not: an 8B model writing
 * freehand Manim gets axis ranges, label placement and API names wrong often
 * enough that most renders fail, and each failure is a retry cycle measured in
 * minutes. A chart it cannot mis-code is worth far more than a chart it could
 * theoretically draw any way it liked.
 *
 * FREEFORM is the escape hatch for visuals no template covers. Those are the
 * only ones that go through code generation and the sandboxed run.
 */
public final class SceneSpec {

    private SceneSpec() {
    }

    public enum VisualKind {
        /** A short line or two of text, for framing a beat. */
        TEXT,
        /** One display equation, rendered with MathTex. */
        EQUATION,
        /** A bar, line or scatter plot built from explicit data. */
        CHART,
        /** Labelled boxes joined by arrows — architectures and data flow. */
        DIAGRAM,
        /** Anything else. Falls through to LLM-generated Manim plus validation. */
        FREEFORM
    }

    public enum ChartKind {
        BAR, LINE, SCATTER
    }

    /**
     * One series of values.
     *
     * @param name   legend label
     * @param values one value per category, same length and order as the chart's categories
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Series(String name, List<Double> values) {
        public List<Double> safeValues() {
            return values == null ? List.of() : values;
        }
    }

    /**
     * A plot built from numbers taken out of the paper.
     *
     * Deliberately explicit: categories and per-series values rather than a
     * formula or a description, so the renderer never has to interpret anything.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chart(
            ChartKind kind,
            String xLabel,
            String yLabel,
            List<String> categories,
            List<Series> series
    ) {
        public List<String> safeCategories() {
            return categories == null ? List.of() : categories;
        }

        public List<Series> safeSeries() {
            return series == null ? List.of() : series;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(String id, String label) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edge(String from, String to, String label) {
    }

    /** Boxes and arrows: encoder/decoder stacks, attention heads, data flow. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Diagram(List<Node> nodes, List<Edge> edges) {
        public List<Node> safeNodes() {
            return nodes == null ? List.of() : nodes;
        }

        public List<Edge> safeEdges() {
            return edges == null ? List.of() : edges;
        }
    }

    /**
     * The visual payload of one beat. Exactly one field matching {@code kind}
     * should be populated.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Visual(
            VisualKind kind,
            /* TEXT */
            String text,
            /* EQUATION — a LaTeX body with no surrounding $ or \[ delimiters */
            String latex,
            /* CHART */
            Chart chart,
            /* DIAGRAM */
            Diagram diagram,
            /* FREEFORM — prose the code generator works from */
            String description
    ) {
    }
}
