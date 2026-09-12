package dev.paperviz.parsing;

import dev.paperviz.ingestion.StorageService;
import dev.paperviz.parsing.PaperParsingService.ParseTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Runs parsing off the request thread.
 *
 * A separate bean from {@link PaperParsingService} on purpose: {@code @Async} and
 * {@code @Transactional} are both proxy-based, so a self-invocation between them
 * would silently lose one of the two.
 */
@Component
public class ParsingJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ParsingJobRunner.class);

    private final PaperParsingService parsingService;
    private final StorageService storage;
    private final GrobidClient grobid;
    private final TeiParser teiParser;

    public ParsingJobRunner(PaperParsingService parsingService,
                            StorageService storage,
                            GrobidClient grobid,
                            TeiParser teiParser) {
        this.parsingService = parsingService;
        this.storage = storage;
        this.grobid = grobid;
        this.teiParser = teiParser;
    }

    @Async("paperVizExecutor")
    public void submit(UUID paperId) {
        run(paperId);
    }

    /** Synchronous entry point, used by the async path and by tests. */
    public void run(UUID paperId) {
        Optional<ParseTarget> claimed;
        try {
            claimed = parsingService.beginParsing(paperId);
        } catch (Exception e) {
            log.error("could not claim paper {} for parsing", paperId, e);
            parsingService.markFailed(paperId, e);
            return;
        }

        if (claimed.isEmpty()) {
            return;
        }
        ParseTarget target = claimed.get();

        try {
            // Deliberately outside any transaction: this is the slow call.
            byte[] pdf = storage.read(target.storagePath());
            String tei = grobid.processFullText(pdf, target.filename());
            ParsedPaper parsed = teiParser.parse(tei);

            if (parsed.sections().isEmpty()) {
                throw new IllegalStateException(
                        "GROBID found no readable sections. The PDF may be a scan with no text "
                                + "layer, which needs OCR before PaperViz can read it.");
            }

            parsingService.storeResult(paperId, parsed);
        } catch (Exception e) {
            log.error("parsing failed for paper {}", paperId, e);
            parsingService.markFailed(paperId, e);
        }
    }
}
