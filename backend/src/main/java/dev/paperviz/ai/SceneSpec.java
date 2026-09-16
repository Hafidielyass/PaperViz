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

    /**
     * Roles drive colour. Matching the reference explainers, each role gets one
     * hue used at full strength for the stroke and about a quarter opacity for
     * the fill, so a viewer can tell an encoder from a decoder at a glance
     * without reading the labels.
     */
    public enum Role {
        INPUT, ENCODER, DECODER, ATTENTION, FEEDFORWARD, OUTPUT, NEUTRAL
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(String id, String label, Role role) {
        public Role safeRole() {
            return role == null ? Role.NEUTRAL : role;
        }
    }

    /**
     * A labelled container holding its own nodes.
     *
     * This is what turns a flat row of boxes into an architecture. "Encoder
     * Stack ×6" wrapping a self-attention and a feed-forward box reads as a
     * structure; the same two boxes side by side read as nothing.
     *
     * @param repeat drawn as "xN" beside the label, for stacked identical layers
     * @param layout STACK for vertically stacked children, ROW for side by side
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Group(
            String id,
            String label,
            Integer repeat,
            String layout,
            Role role,
            List<Node> nodes
    ) {
        public List<Node> safeNodes() {
            return nodes == null ? List.of() : nodes;
        }

        public Role safeRole() {
            return role == null ? Role.NEUTRAL : role;
        }

        public boolean stacked() {
            return layout == null || !"ROW".equalsIgnoreCase(layout);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edge(String from, String to, String label) {
    }

    /**
     * Boxes and arrows: encoder/decoder stacks, attention heads, data flow.
     *
     * Either form works. Bare {@code nodes} give a simple row; {@code groups}
     * give the nested structure that actually explains an architecture. Edges
     * may join a node or a whole group.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Diagram(List<Node> nodes, List<Edge> edges, List<Group> groups) {
        public List<Node> safeNodes() {
            return nodes == null ? List.of() : nodes;
        }

        public List<Edge> safeEdges() {
            return edges == null ? List.of() : edges;
        }

        public List<Group> safeGroups() {
            return groups == null ? List.of() : groups;
        }

        /** Every id an edge is allowed to reference: bare nodes, groups, and grouped nodes. */
        public java.util.Set<String> addressableIds() {
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            safeNodes().forEach(n -> ids.add(n.id()));
            for (Group g : safeGroups()) {
                ids.add(g.id());
                g.safeNodes().forEach(n -> ids.add(n.id()));
            }
            ids.remove(null);
            return ids;
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
