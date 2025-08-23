package com.dienform.tool.dienformtudong.fillrequest.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.form.entity.Form;
import jakarta.persistence.LockModeType;

@Repository
public interface FillRequestRepository extends JpaRepository<FillRequest, UUID> {

        @EntityGraph(attributePaths = {"answerDistributions"})
        @Query("SELECT fr FROM FillRequest fr WHERE fr.form = ?1")
        List<FillRequest> findByForm(Form form);

        @EntityGraph(attributePaths = {"form"})
        @Query("SELECT fr FROM FillRequest fr WHERE fr.id = ?1")
        Optional<FillRequest> findByIdWithFetchForm(UUID id);

        /**
         * Find FillRequest with all related data loaded to avoid LazyInitializationException
         */
        @EntityGraph(attributePaths = {"form", "answerDistributions",
                        "answerDistributions.question", "answerDistributions.option"})
        @Query("SELECT fr FROM FillRequest fr WHERE fr.id = ?1")
        Optional<FillRequest> findByIdWithAllData(UUID id);

        /**
         * Find PENDING campaigns that should start now or in the past
         */
        @EntityGraph(attributePaths = {"form"})
        @Query("SELECT fr FROM FillRequest fr WHERE fr.status = ?1 AND fr.startDate <= ?2")
        List<FillRequest> findByStatusAndStartDateLessThanEqual(FillRequestStatusEnum status,
                        LocalDateTime startDate);

        @Query("SELECT fr FROM FillRequest fr WHERE fr.status = ?1 AND fr.startDate < ?2")
        List<FillRequest> findByStatusAndStartDateLessThan(FillRequestStatusEnum status,
                        LocalDateTime startDate);

        /**
         * Find QUEUED campaigns ordered by priority and queue position
         */
        @EntityGraph(attributePaths = {"form"})
        @Query("SELECT fr FROM FillRequest fr WHERE fr.status = ?1 ORDER BY fr.priority DESC, fr.queuedAt ASC")
        List<FillRequest> findQueuedRequestsOrderedByPriority(FillRequestStatusEnum status);

        /**
         * Find the next queue position for new requests
         */
        @Query("SELECT COALESCE(MAX(fr.queuePosition), 0) + 1 FROM FillRequest fr WHERE fr.status = ?1")
        Integer findNextQueuePosition(FillRequestStatusEnum status);

        /**
         * Update queue positions after a request is processed
         */
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Transactional
        @Query("UPDATE FillRequest fr SET fr.queuePosition = fr.queuePosition - 1 WHERE fr.status = ?1 AND fr.queuePosition > ?2")
        int decrementQueuePositions(FillRequestStatusEnum status, Integer position);

        /**
         * Find requests that need retry (FAILED with retry count < max retries)
         */
        @EntityGraph(attributePaths = {"form"})
        @Query("SELECT fr FROM FillRequest fr WHERE fr.status = ?1 AND fr.retryCount < fr.maxRetries ORDER BY fr.queuedAt ASC")
        List<FillRequest> findFailedRequestsForRetry(FillRequestStatusEnum status);

        /**
         * Count requests by status
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE fr.status = ?1")
        long countByStatus(FillRequestStatusEnum status);

        /**
         * Find requests by status and updated time for recovery
         */
        @EntityGraph(attributePaths = {"form"})
        @Query("SELECT fr FROM FillRequest fr WHERE fr.status = ?1 AND fr.updatedAt < ?2")
        List<FillRequest> findByStatusAndUpdatedAtBefore(FillRequestStatusEnum status,
                        LocalDateTime updatedAt);

        /**
         * Find all requests by status
         */
        @EntityGraph(attributePaths = {"form"})
        @Query("SELECT fr FROM FillRequest fr WHERE fr.status = ?1")
        List<FillRequest> findByStatus(FillRequestStatusEnum status);

        void deleteByForm(Form form);

        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Transactional
        @Query("UPDATE FillRequest fr SET fr.completedSurvey = fr.completedSurvey + 1 WHERE fr.id = ?1 AND fr.completedSurvey < fr.surveyCount")
        int incrementCompletedSurvey(UUID id);

        /**
         * Atomic increment with optimistic locking to prevent race conditions
         */
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Transactional
        @Query("UPDATE FillRequest fr SET fr.completedSurvey = fr.completedSurvey + 1, fr.updatedAt = NOW() WHERE fr.id = ?1 AND fr.completedSurvey < fr.surveyCount")
        int incrementCompletedSurveyAtomic(UUID id);

        /**
         * Fetch a FillRequest with PESSIMISTIC_WRITE lock for safe concurrent increments.
         */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT fr FROM FillRequest fr WHERE fr.id = ?1")
        Optional<FillRequest> findByIdForUpdate(UUID id);

        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Transactional
        @Query("UPDATE FillRequest fr SET fr.status = ?2 WHERE fr.id = ?1")
        int updateStatus(UUID id,
                        com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum status);
}
