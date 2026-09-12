package dev.paperviz.domain.repo;

import dev.paperviz.domain.model.Paper;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaperRepository extends JpaRepository<Paper, UUID> {

    /** Cache lookup: identical bytes for the same owner reuse the existing paper. */
    Optional<Paper> findByOwnerIdAndContentSha256(UUID ownerId, String contentSha256);

    List<Paper> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    Optional<Paper> findByIdAndOwnerId(UUID id, UUID ownerId);
}
