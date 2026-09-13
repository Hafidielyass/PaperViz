package dev.paperviz.domain.repo;

import dev.paperviz.domain.model.Render;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenderRepository extends JpaRepository<Render, UUID> {

    Optional<Render> findByConceptId(UUID conceptId);

    Optional<Render> findBySegmentId(UUID segmentId);

    /** A READY render with the same cache key can be reused verbatim. */
    Optional<Render> findFirstByCacheKeyAndStatus(String cacheKey, dev.paperviz.domain.model.Enums.RenderStatus status);

    List<Render> findByConceptIdIn(List<UUID> conceptIds);
}
