package dev.paperviz.ai;

import dev.paperviz.ai.AiModels.ConceptCandidate;
import dev.paperviz.ai.AiModels.ConceptExtraction;
import dev.paperviz.ai.AiModels.Storyboard;
import dev.paperviz.domain.model.Section;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * All LLM calls for analysing a paper.
 *
 * Kept in one class so every prompt, option set and retry rule for the model
 * lives in a single place rather than being scattered through the pipeline.
 */
@Service
public class PaperAnalysisAi {

    private static final Logger log = LoggerFactory.getLogger(PaperAnalysisAi.class);

    /**
     * Sections longer than this are truncated before being sent.
     *
     * The model runs with a 8192-token window; a very long results section can
     * exceed that on its own, and a silently truncated prompt produces
     * confidently wrong output rather than an error.
     */
    private static final int MAX_SECTION_CHARS = 8000;

    private static final int MAX_ATTEMPTS = 3;

    private final ChatClient chat;
    private final StoryboardValidator validator;
    private final Resource conceptPrompt;
    private final Resource storyboardPrompt;

    public PaperAnalysisAi(ChatClient.Builder chatClientBuilder,
                           StoryboardValidator validator,
                           @Value("classpath:/prompts/concept-extraction.st") Resource conceptPrompt,
                           @Value("classpath:/prompts/storyboard.st") Resource storyboardPrompt) {
        this.chat = chatClientBuilder.build();
        this.validator = validator;
        this.conceptPrompt = conceptPrompt;
        this.storyboardPrompt = storyboardPrompt;
    }

    /**
     * Prompt templates use &lt;angle&gt; placeholders, not braces.
     *
     * The prompts contain literal JSON examples showing the model what a chart
     * or equation payload looks like, and the default StringTemplate renderer
     * reads every brace in those examples as a placeholder — which fails the
     * whole render with "The template string is not valid" before the model is
     * ever called. Angle brackets do not otherwise appear in these prompts.
     */
    private static final StTemplateRenderer TEMPLATE_RENDERER = StTemplateRenderer.builder()
            .startDelimiterToken('<')
            .endDelimiterToken('>')
            .build();

    /** Options shared by every call: JSON mode, low temperature, room for a long section. */
    private OllamaOptions options() {
        return OllamaOptions.builder()
                .format("json")
                .temperature(0.2)
                .numCtx(8192)
                .build();
    }

    public List<ConceptCandidate> extractConcepts(String paperTitle, Section section) {
        Map<String, Object> params = Map.of(
                "paperTitle", nullSafe(paperTitle, "Untitled"),
                "heading", nullSafe(section.getHeading(), "(untitled section)"),
                "sectionType", section.getSectionType().name(),
                "sectionText", truncate(section.getRawText()),
                "formulas", nullSafe(section.getLatex(), "(none)"));

        String rendered = render(conceptPrompt, params);
        ConceptExtraction result = callWithRetry(
                "concept extraction for section " + section.getOrdinal(),
                () -> chat.prompt()
                        .user(rendered)
                        .options(options())
                        .call()
                        .entity(ConceptExtraction.class));

        if (result == null) {
            return List.of();
        }

        // The model is told to return only animatable concepts, but it sometimes
        // returns rejected ones with animate=false. Honour that flag.
        List<ConceptCandidate> kept = result.safeConcepts().stream()
                .filter(c -> c.title() != null && !c.title().isBlank())
                .filter(ConceptCandidate::shouldAnimate)
                .toList();

        log.info("section {} ({}): {} concept(s) worth animating",
                section.getOrdinal(), section.getHeading(), kept.size());
        return kept;
    }

    /**
     * Builds a storyboard, re-prompting with the validator's complaints when the
     * first attempt breaks the timing or beat-count rules.
     */
    public StoryboardResult buildStoryboard(String paperTitle, Section section, ConceptCandidate concept) {
        return buildStoryboard(
                paperTitle,
                nullSafe(section.getHeading(), "(untitled section)"),
                nullSafe(concept.title(), "Concept"),
                concept.conceptType() == null ? "OTHER" : concept.conceptType().name(),
                nullSafe(concept.description(), ""),
                section.getRawText(),
                section.getLatex());
    }

    /**
     * Builds a storyboard for one explainer segment.
     *
     * A segment spans several parsed sections, so its source text is assembled
     * by the caller rather than read off a single Section.
     */
    public StoryboardResult buildStoryboardForSegment(String paperTitle,
                                                      String segmentTitle,
                                                      String focus,
                                                      String sourceText,
                                                      String formulas) {
        return buildStoryboard(
                paperTitle, segmentTitle, segmentTitle, "OTHER", focus, sourceText, formulas);
    }

    private StoryboardResult buildStoryboard(String paperTitle,
                                             String heading,
                                             String conceptTitle,
                                             String conceptType,
                                             String conceptDescription,
                                             String sourceText,
                                             String formulas) {
        Map<String, Object> base = Map.of(
                "paperTitle", nullSafe(paperTitle, "Untitled"),
                "heading", nullSafe(heading, "(untitled section)"),
                "conceptTitle", nullSafe(conceptTitle, "Concept"),
                "conceptType", nullSafe(conceptType, "OTHER"),
                "conceptDescription", nullSafe(conceptDescription, ""),
                "sectionText", truncate(sourceText),
                "formulas", nullSafe(formulas, "(none)"));
        String label = nullSafe(conceptTitle, "concept");

        String correction = "";
        StoryboardValidator.Result lastCheck = null;

        String rendered = render(storyboardPrompt, base);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Storyboard sb;
            try {
                // A blank system message is rejected outright ("text cannot be
                // null or empty"), so only attach one when there is a correction.
                ChatClient.ChatClientRequestSpec request = chat.prompt()
                        .user(rendered)
                        .options(options());
                if (!correction.isBlank()) {
                    request = request.system(correction);
                }
                sb = request.call().entity(Storyboard.class);
            } catch (Exception e) {
                log.warn("storyboard attempt {}/{} for '{}' failed to parse: {}",
                        attempt, MAX_ATTEMPTS, label, e.getMessage());
                correction = "Your previous reply was not valid JSON matching the requested "
                        + "shape. Return only the JSON object, nothing else.";
                continue;
            }

            lastCheck = validator.validate(sb);
            if (lastCheck.ok()) {
                log.info("storyboard for '{}' accepted on attempt {} ({} beats)",
                        label, attempt, sb.safeBeats().size());
                return new StoryboardResult(sb, attempt, List.of());
            }

            // Timing is arithmetic, not judgement. Stretch overrunning beats and
            // re-check before spending another model call — losing a good concept
            // because one beat was a few words long is not a real failure.
            Storyboard repaired = validator.repairTiming(sb);
            if (repaired != sb) {
                StoryboardValidator.Result afterRepair = validator.validate(repaired);
                if (afterRepair.ok()) {
                    log.info("storyboard for '{}' accepted on attempt {} after timing repair",
                            label, attempt);
                    return new StoryboardResult(repaired, attempt, List.of());
                }
                lastCheck = afterRepair;
            }

            log.warn("storyboard attempt {}/{} for '{}' rejected: {}",
                    attempt, MAX_ATTEMPTS, label, lastCheck.describe());
            correction = "Your previous storyboard was rejected for these reasons: "
                    + lastCheck.describe()
                    + " Fix every one of them. The beat durations must sum exactly to totalSeconds.";
        }

        // Out of attempts. Return the last problems so the UI can show why,
        // rather than pretending nothing was produced.
        return new StoryboardResult(null, MAX_ATTEMPTS,
                lastCheck == null ? List.of("Model never returned parseable JSON.") : lastCheck.problems());
    }

    private <T> T callWithRetry(String what, java.util.function.Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                last = e;
                log.warn("{} attempt {}/{} failed: {}", what, attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        log.error("{} gave up after {} attempts", what, MAX_ATTEMPTS, last);
        return null;
    }

    /** Renders a prompt resource with the angle-bracket delimiters. */
    private String render(Resource template, Map<String, Object> params) {
        return PromptTemplate.builder()
                .resource(template)
                .renderer(TEMPLATE_RENDERER)
                .build()
                .render(params);
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_SECTION_CHARS) {
            return text;
        }
        return text.substring(0, MAX_SECTION_CHARS) + "\n[section truncated]";
    }

    private String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * @param storyboard null when every attempt was rejected
     * @param attempts   how many calls it took
     * @param problems   why it was rejected, when it was
     */
    public record StoryboardResult(Storyboard storyboard, int attempts, List<String> problems) {
        public boolean ok() {
            return storyboard != null;
        }
    }
}
