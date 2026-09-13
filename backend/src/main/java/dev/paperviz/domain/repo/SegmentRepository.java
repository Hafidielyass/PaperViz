package dev.paperviz.domain.repo;

import dev.paperviz.domain.model.Segment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SegmentRepository extends JpaRepository<Segment, UUID> {

    List<Segment> findByPaperIdOrderByOrdinalAsc(UUID paperId);

    Optional<Segment> findByPaperIdAndOrdinal(UUID paperId, Integer ordinal);

    void deleteByPaperId(UUID paperId);
}
