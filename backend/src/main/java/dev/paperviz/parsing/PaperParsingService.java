package dev.paperviz.parsing;

import dev.paperviz.domain.model.Enums.PaperStatus;
import dev.paperviz.domain.model.Paper;
import dev.paperviz.domain.model.Section;
import dev.paperviz.domain.repo.PaperRepository;
import dev.paperviz.domain.repo.SectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The transactional half of parsing.
 *
 * Each method is a short unit of database work. The slow part — the GROBID call
 * — deliberately lives outside these boundaries in {@link ParsingJobRunner}, so
 * a connection is never held for the minute or two GROBID can take.
 */
@Service
public class PaperParsingService {

    private static final Logger log = LoggerFactory.getLogger(PaperParsingService.class);

    private final PaperRepository papers;
    private final SectionRepository sections;

    public PaperParsingService(PaperRepository papers, SectionRepository sections) {
        this.papers = papers;
        this.sections = sections;
    }

    /**
     * Claims the paper for parsing.
     *
     * @return what the runner needs to do the work, or empty if another worker
     *         already claimed it
     */
    @Transactional
    public java.util.Optional<ParseTarget> beginParsing(UUID paperId) {
        Paper paper = papers.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("No such paper: " + paperId));

        if (paper.getStatus() == PaperStatus.PARSING) {
            log.info("paper {} is already being parsed, skipping", paperId);
            return java.util.Optional.empty();
        }

        paper.setStatus(PaperStatus.PARSING);
        paper.setStatusDetail("Extracting structure with GROBID");
        papers.save(paper);

        return java.util.Optional.of(
                new ParseTarget(paper.getId(), paper.getStoragePath(), paper.getOriginalFilename()));
    }

    @Transactional
    public int storeResult(UUID paperId, ParsedPaper parsed) {
        Paper paper = papers.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("No such paper: " + paperId));

        // Re-parsing replaces prior sections rather than appending to them.
        sections.deleteByPaperId(paperId);
        sections.flush();

        List<Section> toSave = new ArrayList<>();
        int ordinal = 0;
        for (ParsedSection ps : parsed.sections()) {
            Section section = new Section();
            section.setPaperId(paperId);
            section.setOrdinal(ordinal++);
            section.setSectionType(ps.type());
            section.setHeading(ps.heading());
            section.setRawText(ps.bodyText());
            section.setLatex(ps.latex());
            toSave.add(section);
        }
        sections.saveAll(toSave);

        if (parsed.title() != null && !parsed.title().isBlank()) {
            paper.setTitle(parsed.title());
        }
        if (parsed.authors() != null && !parsed.authors().isBlank()) {
            paper.setAuthors(parsed.authors());
        }
        paper.setStatus(PaperStatus.PARSED);
        paper.setStatusDetail("%d sections extracted".formatted(toSave.size()));
        papers.save(paper);

        log.info("paper {} parsed into {} sections", paperId, toSave.size());
        return toSave.size();
    }

    @Transactional
    public void markFailed(UUID paperId, Throwable cause) {
        papers.findById(paperId).ifPresent(paper -> {
            paper.setStatus(PaperStatus.FAILED);
            paper.setStatusDetail(rootMessage(cause));
            papers.save(paper);
        });
    }

    private String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = root.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /** The minimum a worker needs to parse a paper, read inside a transaction. */
    public record ParseTarget(UUID paperId, String storagePath, String filename) {
    }
}
