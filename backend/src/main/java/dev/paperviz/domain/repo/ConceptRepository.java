package dev.paperviz.domain.repo;

import dev.paperviz.domain.model.Concept;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ConceptRepository extends JpaRepository<Concept, UUID> {

    List<Concept> findBySectionIdOrderByOrdinalAsc(UUID sectionId);

    @Query("""
            select c from Concept c
            where c.sectionId in (
                select s.id from Section s where s.paperId = :paperId
            )
            order by c.ordinal asc
            """)
    List<Concept> findByPaperId(@Param("paperId") UUID paperId);

    void deleteBySectionId(UUID sectionId);
}
