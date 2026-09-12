package dev.paperviz.api;

import dev.paperviz.api.PaperDtos.PaperDetail;
import dev.paperviz.api.PaperDtos.PaperSummary;
import dev.paperviz.api.PaperDtos.SectionView;
import dev.paperviz.api.PaperDtos.UploadResponse;
import dev.paperviz.config.PaperVizProperties;
import dev.paperviz.domain.model.Enums.PaperStatus;
import dev.paperviz.domain.model.Paper;
import dev.paperviz.domain.model.Section;
import dev.paperviz.domain.repo.PaperRepository;
import dev.paperviz.domain.repo.SectionRepository;
import dev.paperviz.ingestion.IngestionException;
import dev.paperviz.ingestion.PaperIngestionService;
import dev.paperviz.ingestion.PaperIngestionService.IngestResult;
import dev.paperviz.ingestion.StorageService;
import dev.paperviz.parsing.ParsingJobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/papers")
public class PaperController {

    private static final Logger log = LoggerFactory.getLogger(PaperController.class);

    private final PaperIngestionService ingestion;
    private final ParsingJobRunner parsingJobs;
    private final PaperRepository papers;
    private final SectionRepository sections;
    private final StorageService storage;
    private final PaperVizProperties props;

    public PaperController(PaperIngestionService ingestion,
                           ParsingJobRunner parsingJobs,
                           PaperRepository papers,
                           SectionRepository sections,
                           StorageService storage,
                           PaperVizProperties props) {
        this.ingestion = ingestion;
        this.parsingJobs = parsingJobs;
        this.papers = papers;
        this.sections = sections;
        this.storage = storage;
        this.props = props;
    }

    /**
     * Upload a PDF the user already has access to.
     *
     * Returns as soon as the file is stored; GROBID runs in the background and
     * the client polls {@code GET /api/papers/{id}} for status.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }

        IngestResult result = ingestion.ingestUpload(bytes, file.getOriginalFilename());
        Paper paper = result.paper();

        // A cache hit that already parsed is reused as-is. One that failed or
        // never finished gets another attempt.
        boolean needsParsing = !result.cacheHit()
                || paper.getStatus() == PaperStatus.UPLOADED
                || paper.getStatus() == PaperStatus.FAILED;

        if (needsParsing) {
            parsingJobs.submit(paper.getId());
        }

        String message = result.cacheHit() && !needsParsing
                ? "Already processed — opening the cached version."
                : "Upload accepted. Extracting structure now.";

        return new UploadResponse(paper.getId(), paper.getStatus().name(), result.cacheHit(), message);
    }

    @GetMapping
    public List<PaperSummary> list() {
        return papers.findByOwnerIdOrderByCreatedAtDesc(props.getDefaultOwnerId()).stream()
                .map(p -> PaperSummary.of(p, sections.findByPaperIdOrderByOrdinalAsc(p.getId()).size()))
                .toList();
    }

    @GetMapping("/{id}")
    public PaperDetail get(@PathVariable UUID id) {
        Paper paper = requireOwned(id);
        List<Section> found = sections.findByPaperIdOrderByOrdinalAsc(id);
        return new PaperDetail(
                PaperSummary.of(paper, found.size()),
                found.stream().map(SectionView::of).toList());
    }

    @GetMapping("/{id}/sections")
    public List<SectionView> listSections(@PathVariable UUID id) {
        requireOwned(id);
        return sections.findByPaperIdOrderByOrdinalAsc(id).stream().map(SectionView::of).toList();
    }

    /**
     * Streams the original PDF back to its owner.
     *
     * Served through the API rather than as a static path, so an uploaded paper
     * is never reachable by anyone but the account that uploaded it.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable UUID id) {
        Paper paper = requireOwned(id);
        if (!storage.exists(paper.getStoragePath())) {
            throw new ResponseStatusException(NOT_FOUND, "The stored file for this paper is missing.");
        }
        byte[] bytes = storage.read(paper.getStoragePath());

        String filename = paper.getOriginalFilename() == null ? "paper.pdf" : paper.getOriginalFilename();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(filename).build().toString())
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    /** Re-run parsing, e.g. after a GROBID failure. */
    @PostMapping("/{id}/reparse")
    public UploadResponse reparse(@PathVariable UUID id) {
        Paper paper = requireOwned(id);
        if (paper.getStatus() == PaperStatus.PARSING) {
            throw new IngestionException("This paper is already being parsed.");
        }
        parsingJobs.submit(paper.getId());
        return new UploadResponse(paper.getId(), PaperStatus.PARSING.name(), false, "Re-parsing started.");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        Paper paper = requireOwned(id);
        // Sections cascade in the schema; the stored bytes do not, so remove them here.
        storage.delete(paper.getStoragePath());
        papers.delete(paper);
        log.info("deleted paper {}", id);
        return ResponseEntity.noContent().build();
    }

    private Paper requireOwned(UUID id) {
        return papers.findByIdAndOwnerId(id, props.getDefaultOwnerId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such paper."));
    }
}
