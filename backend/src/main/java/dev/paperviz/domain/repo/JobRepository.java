package dev.paperviz.domain.repo;

import dev.paperviz.domain.model.Enums.JobStatus;
import dev.paperviz.domain.model.Job;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByPaperIdOrderByCreatedAtAsc(UUID paperId);

    /**
     * Claim the next pending job. SKIP LOCKED keeps concurrent workers from
     * fighting over the same row without needing an external broker.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select j from Job j where j.status = :status order by j.createdAt asc")
    List<Job> claimNext(JobStatus status, Limit limit);
}
