package dev.paperviz.ai;

import dev.paperviz.ai.AiModels.SegmentCopy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds the explainer copy to its length and voice rules.
 *
 * The prompt asks for all of this, and the model complies most of the time —
 * which is exactly why it needs checking. A part that quietly runs to 160 words
 * or cites "Appendix B.1" is the difference between an explainer and a summary
 * of a document, and neither failure is visible until someone reads the page.
 */
@Component
public class SegmentCopyValidator {

    /** Hard cap. A part is a short read beside a video, not a section summary. */
    public static final int MAX_WORDS = 90;
    public static final int MAX_PARAGRAPHS = 2;
    static final int MIN_WORDS = 25;

    /** Above this token overlap, a takeaway is just restating the body. */
    static final double TAKEAWAY_SIMILARITY_LIMIT = 0.6;

    /**
     * Things that betray a summary of a document rather than an explanation of
     * an idea. Each is written to catch the phrasing, not any word inside it.
     */
    private static final List<Banned> BANNED = List.of(
            new Banned(Pattern.compile("\\bet\\.?\\s?al\\.?", Pattern.CASE_INSENSITIVE),
                    "an academic citation"),
            new Banned(Pattern.compile("\\[\\d+(,\\s*\\d+)*]"),
                    "a numeric citation"),
            // Case-insensitivity is scoped to the keyword group on purpose. Applied
            // to the whole pattern it also loosens [0-9A-Z], which then matches a
            // lowercase letter — and "section of" reads as a numbered reference.
            new Banned(Pattern.compile("\\b(?i:figure|fig\\.|table|section|appendix|equation|eq\\.)\\s*[0-9A-Z]"),
                    "a reference to a numbered figure, table or section"),
            new Banned(Pattern.compile("\\bthis (paper|work|study|article)\\b", Pattern.CASE_INSENSITIVE),
                    "\"this paper\""),
            new Banned(Pattern.compile("\\bthe (authors|paper|study)\\b", Pattern.CASE_INSENSITIVE),
                    "\"the authors\" or \"the paper\""),
            new Banned(Pattern.compile("\\bas (shown|described|discussed) (in|above|below)\\b",
                    Pattern.CASE_INSENSITIVE),
                    "a cross-reference"),
            new Banned(Pattern.compile("\\bwe (propose|present|introduce|show)\\b", Pattern.CASE_INSENSITIVE),
                    "paper voice (\"we propose\")")
    );

    public Result validate(SegmentCopy copy) {
        List<String> problems = new ArrayList<>();

        if (copy == null) {
            return new Result(List.of("No copy was produced."));
        }
        if (copy.title() == null || copy.title().isBlank()) {
            problems.add("Missing title.");
        } else if (wordCount(copy.title()) > 8) {
            problems.add("Title is %d words; keep it to 2-6.".formatted(wordCount(copy.title())));
        }

        String body = copy.body() == null ? "" : copy.body().trim();
        if (body.isEmpty()) {
            problems.add("Missing body.");
            return new Result(problems);
        }

        int words = wordCount(body);
        if (words > MAX_WORDS) {
            problems.add("Body is %d words; the limit is %d. Cut, do not compress."
                    .formatted(words, MAX_WORDS));
        }
        if (words < MIN_WORDS) {
            problems.add("Body is only %d words; say enough to set the animation up."
                    .formatted(words));
        }

        int paragraphs = paragraphsOf(body).size();
        if (paragraphs > MAX_PARAGRAPHS) {
            problems.add("Body has %d paragraphs; the limit is %d."
                    .formatted(paragraphs, MAX_PARAGRAPHS));
        }

        for (Banned banned : BANNED) {
            Matcher matcher = banned.pattern().matcher(body);
            if (matcher.find()) {
                problems.add("Body contains %s (\"%s\"). Explain the idea, not the document."
                        .formatted(banned.description(), matcher.group().trim()));
            }
        }

        return new Result(problems);
    }

    /**
     * Trims the body to the last complete sentence inside the word limit.
     *
     * Deterministic repair, in the same spirit as the storyboard timing fix: an
     * over-long body is arithmetic, not judgement, and cutting at a sentence
     * boundary beats spending another model call to be told the same thing.
     * Returns the original when nothing needs cutting.
     */
    public String trimToLimit(String body) {
        if (body == null || wordCount(body) <= MAX_WORDS) {
            return body;
        }

        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (String sentence : body.trim().split("(?<=[.!?])\\s+")) {
            int length = wordCount(sentence);
            if (used + length > MAX_WORDS) {
                break;
            }
            if (kept.length() > 0) {
                kept.append(' ');
            }
            kept.append(sentence.trim());
            used += length;
        }

        // Every sentence was itself over the limit: fall back to a word cut.
        if (kept.length() == 0) {
            String[] tokens = body.trim().split("\\s+");
            return String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, MAX_WORDS));
        }
        return kept.toString();
    }

    /**
     * A takeaway earns its place only by adding something.
     *
     * @return true when it is worth keeping
     */
    public boolean takeawayAddsSomething(String takeaway, String body) {
        if (takeaway == null || takeaway.isBlank()) {
            return false;
        }
        Set<String> takeawayTokens = tokens(takeaway);
        if (takeawayTokens.isEmpty()) {
            return false;
        }
        for (String sentence : (body == null ? "" : body).split("(?<=[.!?])\\s+")) {
            if (similarity(takeawayTokens, tokens(sentence)) >= TAKEAWAY_SIMILARITY_LIMIT) {
                return false;
            }
        }
        return true;
    }

    public static List<String> paragraphsOf(String body) {
        List<String> result = new ArrayList<>();
        for (String paragraph : (body == null ? "" : body).split("\\n\\s*\\n")) {
            if (!paragraph.isBlank()) {
                result.add(paragraph.trim());
            }
        }
        return result;
    }

    private double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 3) {
                result.add(token);
            }
        }
        return result;
    }

    private int wordCount(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    private record Banned(Pattern pattern, String description) {
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
