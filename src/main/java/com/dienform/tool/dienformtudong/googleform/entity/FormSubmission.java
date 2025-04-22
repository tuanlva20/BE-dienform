package com.dienform.tool.dienformtudong.googleform.entity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity representing a form submission execution Maps to the survey_execution table in the
 * database
 */
@Data
//@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
//@Table(name = "survey_execution")
public class FormSubmission {
//  @Id
//  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID fillRequestId;

//  @Column(name = "execution_time", nullable = false)
  private LocalDateTime executionTime;

//  @Column(name = "status", nullable = false, length = 50)
  private String status;

//  @Column(name = "error_message")
  private String errorMessage;

//  @JdbcTypeCode(SqlTypes.JSON)
//  @Column(name = "response_data", columnDefinition = "json")
  private Map<String, Object> responseData;
}
