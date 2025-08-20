package com.dienform.tool.dienformtudong.fillrequest.service;

import java.util.UUID;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.realtime.FillRequestRealtimeGateway;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dedicated service for safely incrementing FillRequest counters with short, independent
 * transactions to avoid lock timeout issues during concurrent form filling operations.
 * 
 * Uses REQUIRES_NEW transaction propagation to ensure each increment operation runs in its own
 * transaction, independent of any parent transaction context.
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
   * Safely increment completedSurvey counter with retry mechanism. Uses REQUIRES_NEW to create
   * independent short transaction that won't interfere with long-running parent transactions.
   * 
   * @param fillRequestId the fill request ID to increment
   * @return true if increment was successful, false otherwise
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Retryable(value = {Exception.class}, maxAttempts = 5,
      backoff = @Backoff(delay = 100, multiplier = 2.0, maxDelay = 2000))
  public boolean incrementCompletedSurvey(UUID fillRequestId) {
    try {
      log.info("=== INCREMENT ATTEMPT START ===");
      log.info("FillRequest ID: {}", fillRequestId);

      // Get current state before increment
      var beforeState = fillRequestRepository.findById(fillRequestId);
      if (beforeState.isPresent()) {
        var fr = beforeState.get();
        log.info("Before increment - completedSurvey: {}, surveyCount: {}", fr.getCompletedSurvey(),
            fr.getSurveyCount());
      }

      int updated = fillRequestRepository.incrementCompletedSurvey(fillRequestId);
      log.info("Database update result: {} rows affected", updated);

      // Get state after increment
      var afterState = fillRequestRepository.findById(fillRequestId);
      if (afterState.isPresent()) {
        var fr = afterState.get();
        log.info("After increment - completedSurvey: {}, surveyCount: {}", fr.getCompletedSurvey(),
            fr.getSurveyCount());
      }

      if (updated > 0) {
        log.info("Successfully incremented completedSurvey for fillRequest: {}", fillRequestId);

        // Emit realtime update after successful increment
        emitRealtimeUpdate(fillRequestId);

        log.info("=== INCREMENT ATTEMPT SUCCESS ===");
        return true;
      } else {
        log.warn(
            "No rows updated when incrementing completedSurvey for fillRequest: {} (may have reached surveyCount limit)",
            fillRequestId);
        log.info("=== INCREMENT ATTEMPT FAILED (no rows updated) ===");
        return false;
      }
    } catch (Exception e) {
      log.error("Failed to increment completedSurvey for fillRequest: {} - {}", fillRequestId,
          e.getMessage(), e);
      log.info("=== INCREMENT ATTEMPT FAILED (exception) ===");
      throw e; // Re-throw to trigger retry
    }
  }

  /**
   * Safely increment completedSurvey with additional delay to reduce contention. This method
   * includes a small random delay before the increment to help reduce simultaneous database access
   * when multiple threads are working.
   * 
   * @param fillRequestId the fill request ID to increment
   * @return true if increment was successful, false otherwise
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Retryable(value = {Exception.class}, maxAttempts = 5,
      backoff = @Backoff(delay = 200, multiplier = 2.0, maxDelay = 3000))
  public boolean incrementCompletedSurveyWithDelay(UUID fillRequestId) {
    try {
      // Add small random delay to reduce contention
      long delay = 50 + (long) (Math.random() * 100); // 50-150ms
      Thread.sleep(delay);

      return incrementCompletedSurvey(fillRequestId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Thread interrupted while incrementing completedSurvey for fillRequest: {}",
          fillRequestId);
      return false;
    }
  }

  /**
   * Get current completed survey count without transaction overhead.
   * 
   * @param fillRequestId the fill request ID
   * @return current completed survey count, or 0 if not found
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
   * Debug method to test increment functionality
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void debugIncrement(UUID fillRequestId) {
    try {
      log.info("=== DEBUG INCREMENT START ===");
      log.info("FillRequest ID: {}", fillRequestId);

      // Get current state
      var fillRequest = fillRequestRepository.findById(fillRequestId);
      if (fillRequest.isPresent()) {
        var fr = fillRequest.get();
        log.info("Current completedSurvey: {}", fr.getCompletedSurvey());
        log.info("Survey count: {}", fr.getSurveyCount());
        log.info("Status: {}", fr.getStatus());
      } else {
        log.error("FillRequest not found: {}", fillRequestId);
        return;
      }

      // Try increment
      int updated = fillRequestRepository.incrementCompletedSurvey(fillRequestId);
      log.info("Increment result: {} rows updated", updated);

      // Get state after increment
      fillRequest = fillRequestRepository.findById(fillRequestId);
      if (fillRequest.isPresent()) {
        var fr = fillRequest.get();
        log.info("After increment completedSurvey: {}", fr.getCompletedSurvey());
      }

      log.info("=== DEBUG INCREMENT END ===");
    } catch (Exception e) {
      log.error("Debug increment failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Direct database test method
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW,
      isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public void directDatabaseTest(UUID fillRequestId) {
    try {
      log.info("=== DIRECT DATABASE TEST START ===");

      // Test 1: Direct SQL query to check current state
      log.info("Test 1: Checking current state via repository...");
      var fillRequest = fillRequestRepository.findById(fillRequestId);
      if (fillRequest.isPresent()) {
        var fr = fillRequest.get();
        log.info("Repository read - completedSurvey: {}, surveyCount: {}", fr.getCompletedSurvey(),
            fr.getSurveyCount());
      }

      // Test 2: Try manual update
      log.info("Test 2: Trying manual update...");
      var manualUpdate = fillRequestRepository.findById(fillRequestId);
      if (manualUpdate.isPresent()) {
        var fr = manualUpdate.get();
        int oldValue = fr.getCompletedSurvey();
        fr.setCompletedSurvey(oldValue + 1);
        fillRequestRepository.save(fr);
        log.info("Manual update - old: {}, new: {}", oldValue, fr.getCompletedSurvey());
      }

      // Test 3: Try atomic update again
      log.info("Test 3: Trying atomic update...");
      int updated = fillRequestRepository.incrementCompletedSurvey(fillRequestId);
      log.info("Atomic update result: {} rows updated", updated);

      // Test 4: Final state check
      log.info("Test 4: Final state check...");
      var finalState = fillRequestRepository.findById(fillRequestId);
      if (finalState.isPresent()) {
        var fr = finalState.get();
        log.info("Final state - completedSurvey: {}, surveyCount: {}", fr.getCompletedSurvey(),
            fr.getSurveyCount());
      }

      log.info("=== DIRECT DATABASE TEST END ===");
    } catch (Exception e) {
      log.error("Direct database test failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Raw SQL test method to bypass JPA
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void rawSqlTest(UUID fillRequestId) {
    try {
      log.info("=== RAW SQL TEST START ===");

      // Test 1: Raw SQL query
      log.info("Test 1: Raw SQL query...");
      jakarta.persistence.Query query = entityManager.createNativeQuery(
          "SELECT completed_survey, survey_count FROM fill_request WHERE id = ?");
      query.setParameter(1, fillRequestId.toString());
      Object[] result = (Object[]) query.getSingleResult();
      log.info("Raw SQL result - completedSurvey: {}, surveyCount: {}", result[0], result[1]);

      // Test 2: Raw SQL update
      log.info("Test 2: Raw SQL update...");
      jakarta.persistence.Query updateQuery = entityManager.createNativeQuery(
          "UPDATE fill_request SET completed_survey = completed_survey + 1 WHERE id = ? AND completed_survey < survey_count");
      updateQuery.setParameter(1, fillRequestId.toString());
      int updated = updateQuery.executeUpdate();
      log.info("Raw SQL update result: {} rows affected", updated);

      // Test 3: Verify update
      log.info("Test 3: Verify update...");
      query = entityManager.createNativeQuery(
          "SELECT completed_survey, survey_count FROM fill_request WHERE id = ?");
      query.setParameter(1, fillRequestId.toString());
      result = (Object[]) query.getSingleResult();
      log.info("After raw SQL update - completedSurvey: {}, surveyCount: {}", result[0], result[1]);

      log.info("=== RAW SQL TEST END ===");
    } catch (Exception e) {
      log.error("Raw SQL test failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Emit realtime update after successful increment
   */
  private void emitRealtimeUpdate(UUID fillRequestId) {
    try {
      var fillRequest = fillRequestRepository.findById(fillRequestId).orElse(null);
      if (fillRequest != null && fillRequest.getForm() != null) {
        // Only emit if there's a meaningful change in progress
        // This prevents duplicate emissions when status hasn't changed
        if (fillRequest.getCompletedSurvey() > 0) {
          // Update status if needed
          String currentStatus =
              fillRequest.getStatus() != null ? fillRequest.getStatus().name() : null;
          String newStatus = currentStatus;

          if ("FAILED".equals(currentStatus)) {
            // If there's progress and status was FAILED, update to IN_PROCESS or COMPLETED
            if (fillRequest.getCompletedSurvey() >= fillRequest.getSurveyCount()) {
              newStatus =
                  com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.COMPLETED
                      .name();
              fillRequest.setStatus(
                  com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.COMPLETED);
            } else {
              newStatus =
                  com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.IN_PROCESS
                      .name();
              fillRequest.setStatus(
                  com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.IN_PROCESS);
            }

            // Save the updated status
            fillRequestRepository.save(fillRequest);
            log.info("Updated status from FAILED to {} for fillRequest: {} (progress: {}/{})",
                newStatus, fillRequestId, fillRequest.getCompletedSurvey(),
                fillRequest.getSurveyCount());
          } else if (fillRequest.getCompletedSurvey() >= fillRequest.getSurveyCount()
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
                  .requestId(fillRequest.getId().toString()).status(newStatus) // Use the updated
                                                                               // status
                  .completedSurvey(fillRequest.getCompletedSurvey())
                  .surveyCount(fillRequest.getSurveyCount())
                  .updatedAt(java.time.Instant.now().toString()).build();

          // Emit to form room only (avoid duplicate user-specific emissions)
          realtimeGateway.emitUpdate(fillRequest.getForm().getId().toString(), event);

          log.debug("Emitted progress update for fillRequest: {} - {}/{} with status: {}",
              fillRequestId, fillRequest.getCompletedSurvey(), fillRequest.getSurveyCount(),
              newStatus);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to emit realtime update: {}", e.getMessage());
    }
  }
}
