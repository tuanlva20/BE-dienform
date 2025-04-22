package com.dienform.tool.dienformtudong.surveyexecution.entity;

import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.form.entity.Form;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "survey_execution", schema = "fill_form")
public class SurveyExecution {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "execution_time", nullable = false)
  private LocalDateTime executionTime;

  @Size(max = 50)
  @Column(name = "status", nullable = false, length = 50)
  private String status;

  @Lob
  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "response_data")
  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> responseData;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "fill_request_id", nullable = false)
  private FillRequest fillRequest;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "form_id", nullable = false)
  private Form form;
}
