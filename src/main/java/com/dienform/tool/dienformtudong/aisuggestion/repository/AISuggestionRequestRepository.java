package com.dienform.tool.dienformtudong.aisuggestion.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.aisuggestion.entity.AISuggestionRequestEntity;
import com.dienform.tool.dienformtudong.aisuggestion.entity.AISuggestionRequestEntity.AISuggestionStatus;

/**
 * Repository for AI Suggestion requests with priority-based queue management
 */
@Repository
public interface AISuggestionRequestRepository extends JpaRepository<AISuggestionRequestEntity, UUID> {

    /**
     * Find queued requests ordered by priority (higher first) and creation time (FIFO)
     */
    @EntityGraph(attributePaths = {"requestData"})
    @Query("SELECT ar FROM AISuggestionRequestEntity ar WHERE ar.status = ?1 ORDER BY ar.priority DESC, ar.createdAt ASC")
    List<AISuggestionRequestEntity> findByStatusOrderByPriorityDescCreatedAtAsc(AISuggestionStatus status);

    /**
     * Find requests by status
     */
    @Query("SELECT ar FROM AISuggestionRequestEntity ar WHERE ar.status = ?1")
    List<AISuggestionRequestEntity> findByStatus(AISuggestionStatus status);

    /**
     * Count requests by status
     */
    @Query("SELECT COUNT(ar) FROM AISuggestionRequestEntity ar WHERE ar.status = ?1")
    long countByStatus(AISuggestionStatus status);

    /**
     * Find the next queue position for new requests
     */
    @Query("SELECT COALESCE(MAX(ar.queuePosition), 0) + 1 FROM AISuggestionRequestEntity ar WHERE ar.status = ?1")
    Integer findNextQueuePosition(AISuggestionStatus status);

    /**
     * Update queue positions after a request is processed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE AISuggestionRequestEntity ar SET ar.queuePosition = ar.queuePosition - 1 WHERE ar.status = ?1 AND ar.queuePosition > ?2")
    int decrementQueuePositions(AISuggestionStatus status, Integer position);

    /**
     * Find failed requests that can be retried
     */
    @Query("SELECT ar FROM AISuggestionRequestEntity ar WHERE ar.status = ?1 AND ar.retryCount < ar.maxRetries ORDER BY ar.createdAt ASC")
    List<AISuggestionRequestEntity> findFailedRequestsForRetry(AISuggestionStatus status);

    /**
     * Find requests that have been processing for too long (likely stuck)
     */
    @Query("SELECT ar FROM AISuggestionRequestEntity ar WHERE ar.status = ?1 AND ar.processingStartedAt < ?2")
    List<AISuggestionRequestEntity> findStuckRequests(AISuggestionStatus status, LocalDateTime cutoffTime);

    /**
     * Find requests by form ID
     */
    @Query("SELECT ar FROM AISuggestionRequestEntity ar WHERE ar.formId = ?1 ORDER BY ar.createdAt DESC")
    List<AISuggestionRequestEntity> findByFormId(String formId);

    /**
     * Update request status
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE AISuggestionRequestEntity ar SET ar.status = ?2, ar.updatedAt = ?3 WHERE ar.id = ?1")
    int updateStatus(UUID id, AISuggestionStatus status, LocalDateTime updatedAt);

    /**
     * Update request priority
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE AISuggestionRequestEntity ar SET ar.priority = ?2, ar.updatedAt = ?3 WHERE ar.id = ?1")
    int updatePriority(UUID id, Integer priority, LocalDateTime updatedAt);

    /**
     * Get priority distribution statistics
     */
    @Query("SELECT ar.priority, COUNT(ar) FROM AISuggestionRequestEntity ar WHERE ar.status = ?1 GROUP BY ar.priority ORDER BY ar.priority DESC")
    List<Object[]> getPriorityDistribution(AISuggestionStatus status);

    /**
     * Find requests by priority range
     */
    @Query("SELECT ar FROM AISuggestionRequestEntity ar WHERE ar.status = ?1 AND ar.priority >= ?2 AND ar.priority <= ?3 ORDER BY ar.priority DESC, ar.createdAt ASC")
    List<AISuggestionRequestEntity> findByStatusAndPriorityRange(AISuggestionStatus status, Integer minPriority, Integer maxPriority);
}
