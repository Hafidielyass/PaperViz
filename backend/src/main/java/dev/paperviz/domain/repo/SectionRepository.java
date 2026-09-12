package dev.paperviz.domain.repo;

import dev.paperviz.domain.model.Section;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SectionRepository extends JpaRepository<Section, UUID> {

    List<Section> findByPaperIdOrderByOrdinalAsc(UUID paperId);

    void deleteByPaperId(UUID paperId);
}
