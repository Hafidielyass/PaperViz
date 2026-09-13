package dev.paperviz.ai;

import dev.paperviz.ai.AiModels.Storyboard;
import dev.paperviz.ai.AiModels.StoryboardBeat;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    static final int MIN_BEATS = 2;
    static final int MAX_BEATS = 8;
    static final int MIN_TOTAL_SECONDS = 10;
    static final int MAX_TOTAL_SECONDS = 60;
    static final int MIN_BEAT_SECONDS = 2;
    static final int MAX_BEAT_SECONDS = 15;

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
        }

        problems.addAll(findRepeatedVisuals(beats));

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

            repaired.add(new StoryboardBeat(order, seconds, beat.visual(), beat.narration(), beat.latex()));
        }

        int total = repaired.stream().mapToInt(StoryboardBeat::seconds).sum();
        if (!changed && sb.totalSeconds() != null && sb.totalSeconds() == total) {
            return sb;
        }
        return new Storyboard(sb.title(), sb.summary(), total, repaired);
    }

    /**
     * Beats that describe the same picture mean nothing moves. The model tends
     * to do this by restating the previous beat's scene verbatim.
     */
    private List<String> findRepeatedVisuals(List<StoryboardBeat> beats) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < beats.size(); i++) {
            for (int j = i + 1; j < beats.size(); j++) {
                String a = beats.get(i).visual();
                String b = beats.get(j).visual();
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
