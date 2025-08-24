package com.dienform.tool.dienformtudong.fillrequest.service;

import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.realtime.FillRequestRealtimeGateway;
import com.dienform.tool.dienformtudong.exception.ResourceNotFoundException;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Simple service for incrementing FillRequest counters with atomic updates. Uses clean, simple
 * approach without complex locking mechanisms.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FillRequestCounterService {

  private final FillRequestRepository fillRequestRepository;
  private final FillRequestRealtimeGateway realtimeGateway;
  private final CurrentUserUtil currentUserUtil;

  @PersistenceContext
  private EntityManager entityManager;

  /**
   * Simple atomic increment of completedSurvey counter. Uses clean atomic update without complex
   * locking.
   * 
   * @param fillRequestId the fill request ID to increment
   * @return true if increment was successful, false otherwise
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Retryable(value = {Exception.class}, maxAttempts = 3,
      backoff = @Backoff(delay = 100, multiplier = 1.5, maxDelay = 500))
  public boolean incrementCompletedSurvey(UUID fillRequestId) {
    try {
      log.debug("Incrementing completedSurvey for fillRequest: {}", fillRequestId);

      // Simple atomic update - this was working fine before
      int updated = fillRequestRepository.incrementCompletedSurveyAtomic(fillRequestId);

      if (updated > 0) {
        log.debug("Successfully incremented completedSurvey for fillRequest: {}", fillRequestId);

        // Emit realtime update
        emitRealtimeUpdate(fillRequestId);

        // Auto-update status to COMPLETED if needed
        autoUpdateStatusIfCompleted(fillRequestId);

        return true;
      }

      // Check if already completed
      var fillRequest = fillRequestRepository.findById(fillRequestId);
      if (fillRequest.isPresent()) {
        var fr = fillRequest.get();
        if (fr.getCompletedSurvey() >= fr.getSurveyCount()) {
          log.debug("Cannot increment - already completed: {}/{}", fr.getCompletedSurvey(),
              fr.getSurveyCount());
          return false;
        }
      }

      log.debug("Atomic increment failed for fillRequest: {}", fillRequestId);
      return false;

    } catch (Exception e) {
      log.error("Failed to increment completedSurvey for fillRequest: {} - {}", fillRequestId,
          e.getMessage(), e);
      throw e; // Re-throw to trigger retry
    }
  }

  /**
   * Update status using optimistic locking to prevent deadlocks
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Retryable(value = {ObjectOptimisticLockingFailureException.class}, maxAttempts = 3,
      backoff = @Backoff(delay = 200, multiplier = 1.5, maxDelay = 1000))
  public boolean updateStatus(UUID fillRequestId, FillRequestStatusEnum newStatus) {
    try {
      log.debug("Updating status for fillRequest: {} to {}", fillRequestId, newStatus);

      // Get current fill request with optimistic locking
      var fillRequest = fillRequestRepository.findById(fillRequestId).orElseThrow(
          () -> new ResourceNotFoundException("Fill Request not found with id: " + fillRequestId));

      // Check if transition is valid
      if (!fillRequest.getStatus().canTransitionTo(newStatus)) {
        log.warn("Invalid status transition from {} to {} for fillRequest: {}",
            fillRequest.getStatus(), newStatus, fillRequestId);
        return false;
      }

      // Update status
      fillRequest.setStatus(newStatus);
      fillRequestRepository.save(fillRequest); // Version will be automatically incremented

      log.debug("Successfully updated status for fillRequest: {} to {}", fillRequestId, newStatus);

      // Emit status update
      emitStatusUpdate(fillRequestId, newStatus);
      return true;

    } catch (ObjectOptimisticLockingFailureException e) {
      log.warn("Optimistic lock failed for fillRequest: {}, retrying...", fillRequestId);
      throw e; // Trigger retry
    } catch (Exception e) {
      log.error("Failed to update status for fillRequest: {} - {}", fillRequestId, e.getMessage(),
          e);
      throw e;
    }
  }

  /**
   * Auto-update status to COMPLETED when all surveys are done
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void autoUpdateStatusIfCompleted(UUID fillRequestId) {
    try {
      var fillRequest = fillRequestRepository.findById(fillRequestId);
      if (fillRequest.isPresent()) {
        var fr = fillRequest.get();
        if (fr.getCompletedSurvey() >= fr.getSurveyCount()
            && fr.getStatus() != FillRequestStatusEnum.COMPLETED) {

          log.info("Auto-updating status to COMPLETED for fillRequest: {} (progress: {}/{})",
              fillRequestId, fr.getCompletedSurvey(), fr.getSurveyCount());

          updateStatus(fillRequestId, FillRequestStatusEnum.COMPLETED);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to auto-update status for fillRequest: {} - {}", fillRequestId,
          e.getMessage());
    }
  }

  /**
   * Get current completed survey count.
   */
  public int getCompletedSurveyCount(UUID fillRequestId) {
    try {
      return fillRequestRepository.findById(fillRequestId).map(fr -> fr.getCompletedSurvey())
          .orElse(0);
    } catch (Exception e) {
      log.warn("Failed to get completedSurvey count for fillRequest: {} - {}", fillRequestId,
          e.getMessage());
      return 0;
    }
  }

  /**
   * Simple test method to verify increment functionality
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void testIncrement(UUID fillRequestId) {
    try {
      log.info("=== TESTING INCREMENT FUNCTIONALITY ===");

      // Get current state
      var fillRequest = fillRequestRepository.findById(fillRequestId);
      if (fillRequest.isPresent()) {
        var fr = fillRequest.get();
        log.info("Before increment - completedSurvey: {}, surveyCount: {}", fr.getCompletedSurvey(),
            fr.getSurveyCount());

        // Try increment
        boolean success = incrementCompletedSurvey(fillRequestId);
        log.info("Increment result: {}", success);

        // Get state after increment
        fillRequest = fillRequestRepository.findById(fillRequestId);
        if (fillRequest.isPresent()) {
          fr = fillRequest.get();
          log.info("After increment - completedSurvey: {}, surveyCount: {}",
              fr.getCompletedSurvey(), fr.getSurveyCount());
        }
      } else {
        log.error("FillRequest not found: {}", fillRequestId);
      }

      log.info("=== TEST COMPLETED ===");
    } catch (Exception e) {
      log.error("Test failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Emit realtime update after successful increment
   */
  private void emitRealtimeUpdate(UUID fillRequestId) {
    try {
      var fillRequest = fillRequestRepository.findById(fillRequestId).orElse(null);
      if (fillRequest != null && fillRequest.getForm() != null) {
        // Update status if needed
        String currentStatus =
            fillRequest.getStatus() != null ? fillRequest.getStatus().name() : null;
        String newStatus = currentStatus;

        if (fillRequest.getCompletedSurvey() >= fillRequest.getSurveyCount()
            && !"COMPLETED".equals(currentStatus)) {
          // If all surveys completed but status is not COMPLETED, update it
          newStatus =
              com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.COMPLETED
                  .name();
          fillRequest.setStatus(
              com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.COMPLETED);
          fillRequestRepository.save(fillRequest);
          log.info("Updated status to COMPLETED for fillRequest: {} (progress: {}/{})",
              fillRequestId, fillRequest.getCompletedSurvey(), fillRequest.getSurveyCount());
        }

        com.dienform.realtime.dto.FillRequestUpdateEvent event =
            com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                .formId(fillRequest.getForm().getId().toString())
                .requestId(fillRequest.getId().toString()).status(newStatus)
                .completedSurvey(fillRequest.getCompletedSurvey())
                .surveyCount(fillRequest.getSurveyCount())
                .updatedAt(java.time.Instant.now().toString()).build();

        realtimeGateway.emitUpdate(fillRequest.getForm().getId().toString(), event);
        log.debug("Emitted progress update for fillRequest: {} - {}/{} with status: {}",
            fillRequestId, fillRequest.getCompletedSurvey(), fillRequest.getSurveyCount(),
            newStatus);
      }
    } catch (Exception e) {
      log.warn("Failed to emit realtime update: {}", e.getMessage());
    }
  }

  /**
   * Emit status update
   */
  private void emitStatusUpdate(UUID fillRequestId, FillRequestStatusEnum newStatus) {
    try {
      var fillRequest = fillRequestRepository.findById(fillRequestId).orElse(null);
      if (fillRequest != null && fillRequest.getForm() != null) {
        com.dienform.realtime.dto.FillRequestUpdateEvent event =
            com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                .formId(fillRequest.getForm().getId().toString())
                .requestId(fillRequest.getId().toString()).status(newStatus.name())
                .completedSurvey(fillRequest.getCompletedSurvey())
                .surveyCount(fillRequest.getSurveyCount())
                .updatedAt(java.time.Instant.now().toString()).build();

        realtimeGateway.emitUpdate(fillRequest.getForm().getId().toString(), event);
        log.debug("Emitted status update for fillRequest: {} - status: {}", fillRequestId,
            newStatus);
      }
    } catch (Exception e) {
      log.warn("Failed to emit status update: {}", e.getMessage());
    }
  }
}
