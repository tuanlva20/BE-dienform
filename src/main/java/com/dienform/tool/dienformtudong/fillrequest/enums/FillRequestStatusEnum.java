package com.dienform.tool.dienformtudong.fillrequest.enums;

import java.util.Set;

public enum FillRequestStatusEnum {
  QUEUED, IN_PROCESS, COMPLETED, FAILED;

  /**
   * Check if transition from current status to new status is valid
   */
  public boolean canTransitionTo(FillRequestStatusEnum newStatus) {
    return switch (this) {
      case QUEUED -> newStatus == IN_PROCESS || newStatus == FAILED;
      case IN_PROCESS -> newStatus == COMPLETED || newStatus == FAILED; // ← KHÔNG cho phép về
                                                                        // QUEUED
      case COMPLETED -> false; // Terminal state - cannot change
      case FAILED -> newStatus == QUEUED; // Can retry from FAILED
    };
  }

  /**
   * Get all valid next states from current status
   */
  public Set<FillRequestStatusEnum> getValidNextStates() {
    return switch (this) {
      case QUEUED -> Set.of(IN_PROCESS, FAILED);
      case IN_PROCESS -> Set.of(COMPLETED, FAILED, IN_PROCESS);
      case COMPLETED -> Set.of(); // No valid transitions
      case FAILED -> Set.of(QUEUED);
    };
  }

  /**
   * Check if status is terminal (cannot be changed)
   */
  public boolean isTerminal() {
    return this == COMPLETED;
  }

  /**
   * Check if status allows retry
   */
  public boolean allowsRetry() {
    return this == FAILED;
  }
}


