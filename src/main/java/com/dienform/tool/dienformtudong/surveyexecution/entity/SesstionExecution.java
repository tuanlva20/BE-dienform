package com.dienform.tool.dienformtudong.surveyexecution.entity;

import java.time.LocalDateTime;
import java.util.UUID;
import com.dienform.tool.dienformtudong.form.enums.FormStatusEnum;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity for tracking Google Form fill executions
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "session_execution")
public class SesstionExecution {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "form_id", nullable = false)
  private UUID formId;

  @Column(name = "fill_request_id", nullable = false)
  private UUID fillRequestId;

  @Column(name = "start_time", nullable = false)
  private LocalDateTime startTime;

  @Column(name = "end_time")
  private LocalDateTime endTime;

  @Column(name = "total_executions", nullable = false)
  private int totalExecutions;

  @Column(name = "successful_executions")
  private int successfulExecutions;

  @Column(name = "failed_executions")
  private int failedExecutions;

  @Column(name = "status", nullable = false)
  private FormStatusEnum status;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  private void onCreate() {
    createdAt = LocalDateTime.now();
  }
}
