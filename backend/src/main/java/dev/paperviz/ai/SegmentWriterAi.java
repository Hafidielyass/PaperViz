package dev.paperviz.ai;

import dev.paperviz.ai.AiModels.SegmentCopy;
import dev.paperviz.ai.AiModels.SegmentPlan;
import dev.paperviz.ai.AiModels.SegmentPlanItem;
import dev.paperviz.domain.model.Concept;
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
import java.util.StringJoiner;

/**
 * Turns a parsed paper into the handful of moments the reader actually sees.
 *
 * Two calls, because a whole paper does not fit in an 8k context:
 * <ol>
 *   <li>plan — read a compressed outline, choose the segments and their order</li>
 *   <li>write — for each segment, read only its own source sections and write
 *       the explainer prose</li>
 * </ol>
 */
@Service
public class SegmentWriterAi {

    private static final Logger log = LoggerFactory.getLogger(SegmentWriterAi.class);

    /**
      * How many parts an explainer runs to.
      *
      * The model proposes a count inside this range so a short paper is not
      * padded and a dense one is not truncated; anything outside it is clamped.
      */
     public static final int MIN_SEGMENTS = 6;
     public static final int MAX_SEGMENTS = 8;
     public static final int DEFAULT_SEGMENT_COUNT = 7;

    private static final int MAX_SOURCE_CHARS = 7000;
    private static final int OUTLINE_SNIPPET_CHARS = 160;
    private static final int MAX_ATTEMPTS = 3;

    private static final StTemplateRenderer TEMPLATE_RENDERER = StTemplateRenderer.builder()
            .startDelimiterToken('<')
            .endDelimiterToken('>')
            .build();

    private final ChatClient chat;
    private final SegmentCopyValidator copyValidator;
    private final Resource planPrompt;
    private final Resource writePrompt;

    public SegmentWriterAi(ChatClient.Builder chatClientBuilder,
                           SegmentCopyValidator copyValidator,
                           @Value("classpath:/prompts/segment-plan.st") Resource planPrompt,
                           @Value("classpath:/prompts/segment-write.st") Resource writePrompt) {
        this.chat = chatClientBuilder.build();
        this.copyValidator = copyValidator;
        this.planPrompt = planPrompt;
        this.writePrompt = writePrompt;
    }

    private OllamaOptions options(double temperature) {
        return OllamaOptions.builder()
                .format("json")
                .temperature(temperature)
                .numCtx(8192)
                .build();
    }

    /**
     * Chooses which moments make the explainer.
     *
     * The whole paper is compressed to one line per section plus the concept
     * shortlist, which is what makes this fit in a single call.
     */
    public List<SegmentPlanItem> plan(String paperTitle,
                                      List<Section> sections,
                                      List<Concept> candidates) {
        Map<String, Object> params = Map.of(
                "paperTitle", nullSafe(paperTitle, "Untitled"),
                "outline", buildOutline(sections),
                "candidates", buildCandidateList(candidates));

        String rendered = render(planPrompt, params);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                SegmentPlan plan = chat.prompt()
                        .user(rendered)
                        .options(options(0.3))
                        .call()
                        .entity(SegmentPlan.class);

                List<SegmentPlanItem> items = plan == null ? List.of() : plan.safeSegments().stream()
                        .filter(i -> i.title() != null && !i.title().isBlank())
                        .limit(MAX_SEGMENTS)
                        .toList();

                if (items.size() >= MIN_SEGMENTS) {
                    log.info("planned {} segment(s) for '{}'", items.size(), paperTitle);
                    return items;
                }
                if (!items.isEmpty() && attempt == MAX_ATTEMPTS) {
                    // Short of the range on the last try: a thin explainer beats none.
                    log.warn("planner returned only {} segment(s); keeping them", items.size());
                    return items;
                }
                if (!items.isEmpty()) {
                    log.warn("plan attempt {}/{} returned {} segment(s), below the {} minimum",
                            attempt, MAX_ATTEMPTS, items.size(), MIN_SEGMENTS);
                    continue;
                }
                log.warn("plan attempt {}/{} produced no usable segments", attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
                log.warn("plan attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        return List.of();
    }

    /** Writes the explainer prose for one planned segment. */
    public SegmentCopy write(String paperTitle, SegmentPlanItem item, List<Section> sources) {
        Map<String, Object> params = Map.of(
                "paperTitle", nullSafe(paperTitle, "Untitled"),
                "segmentTitle", nullSafe(item.title(), "Untitled segment"),
                "focus", nullSafe(item.focus(), ""),
                "sourceText", buildSourceText(sources),
                "formulas", buildFormulas(sources));

        String rendered = render(writePrompt, params);
        String correction = "";
        SegmentCopy best = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            SegmentCopy copy;
            try {
                ChatClient.ChatClientRequestSpec request = chat.prompt()
                        .user(rendered)
                        .options(options(0.4));
                if (!correction.isBlank()) {
                    request = request.system(correction);
                }
                copy = request.call().entity(SegmentCopy.class);
            } catch (Exception e) {
                log.warn("write attempt {}/{} for '{}' failed: {}",
                        attempt, MAX_ATTEMPTS, item.title(), e.getMessage());
                correction = "Your previous reply was not valid JSON in the requested shape. "
                        + "Return only the JSON object.";
                continue;
            }

            if (copy == null || copy.body() == null || copy.body().isBlank()) {
                log.warn("write attempt {}/{} for '{}' returned no body",
                        attempt, MAX_ATTEMPTS, item.title());
                continue;
            }
            best = copy;

            SegmentCopyValidator.Result check = copyValidator.validate(copy);
            if (check.ok()) {
                return finish(copy);
            }

            log.warn("copy attempt {}/{} for '{}' rejected: {}",
                    attempt, MAX_ATTEMPTS, item.title(), check.describe());
            correction = "Your previous copy was rejected: " + check.describe()
                    + " Fix every one of these. Stay under "
                    + SegmentCopyValidator.MAX_WORDS + " words.";
        }

        // Out of attempts. Length is arithmetic, so trim it here rather than
        // losing the part; anything else stands as written.
        if (best != null) {
            log.info("keeping the last copy for '{}' after trimming to the word limit",
                    item.title());
            return finish(best);
        }
        return null;
    }

    /** Applies the deterministic repairs: trim to length, drop an empty takeaway. */
    private SegmentCopy finish(SegmentCopy copy) {
        String body = copyValidator.trimToLimit(copy.body());
        String takeaway = copyValidator.takeawayAddsSomething(copy.keyTakeaway(), body)
                ? copy.keyTakeaway()
                : null;
        return new SegmentCopy(copy.title(), body, takeaway, copy.latex());
    }

    /** One line per section: enough to choose from, small enough to all fit. */
    private String buildOutline(List<Section> sections) {
        StringJoiner joiner = new StringJoiner("\n");
        for (Section section : sections) {
            String text = section.getRawText() == null ? "" : section.getRawText();
            String snippet = text.length() > OUTLINE_SNIPPET_CHARS
                    ? text.substring(0, OUTLINE_SNIPPET_CHARS) + "..."
                    : text;
            joiner.add("[%d] %s (%s) — %s".formatted(
                    section.getOrdinal(),
                    nullSafe(section.getHeading(), "untitled"),
                    section.getSectionType().name(),
                    snippet.replace('\n', ' ')));
        }
        return joiner.toString();
    }

    private String buildCandidateList(List<Concept> candidates) {
        if (candidates.isEmpty()) {
            return "(none found)";
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (Concept concept : candidates) {
            joiner.add("- %s [%s]: %s".formatted(
                    concept.getTitle(),
                    concept.getConceptType().name(),
                    nullSafe(concept.getDescription(), "")));
        }
        return joiner.toString();
    }

    private String buildSourceText(List<Section> sources) {
        StringJoiner joiner = new StringJoiner("\n\n");
        int budget = MAX_SOURCE_CHARS;
        for (Section section : sources) {
            if (budget <= 0) {
                break;
            }
            String text = section.getRawText() == null ? "" : section.getRawText();
            if (text.length() > budget) {
                text = text.substring(0, budget) + "\n[truncated]";
            }
            budget -= text.length();
            joiner.add("## %s\n%s".formatted(nullSafe(section.getHeading(), "Section"), text));
        }
        String result = joiner.toString();
        return result.isBlank() ? "(no source text available)" : result;
    }

    private String buildFormulas(List<Section> sources) {
        StringJoiner joiner = new StringJoiner("\n");
        for (Section section : sources) {
            if (section.getLatex() != null && !section.getLatex().isBlank()) {
                joiner.add(section.getLatex());
            }
        }
        return joiner.length() == 0 ? "(none)" : joiner.toString();
    }

    private String render(Resource template, Map<String, Object> params) {
        return PromptTemplate.builder()
                .resource(template)
                .renderer(TEMPLATE_RENDERER)
                .build()
                .render(params);
    }

    private String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
