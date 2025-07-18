package com.dienform.tool.dienformtudong.formstatistic.entity;

import com.dienform.common.entity.AuditEntity;
import com.dienform.tool.dienformtudong.form.entity.Form;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "form_statistic")
public class FormStatistic extends AuditEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "total_survey", nullable = false)
  private Integer totalSurvey;

  @Column(name = "completed_survey", nullable = false)
  private Integer completedSurvey;

  @Column(name = "failed_survey", nullable = false)
  private Integer failedSurvey;

  @Column(name = "error_question", nullable = false)
  private Integer errorQuestion;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "form_id", nullable = false)
  private Form form;

  //    @Id
//    @GeneratedValue(strategy = GenerationType.UUID)
//    @Column(name = "id", updatable = false, nullable = false)
//    private UUID id;
//
////    @Column(name = "form_id", nullable = false)
////    private UUID formId;
//
//    @Column(name = "total_survey", nullable = false)
//    private int totalSurvey;
//
//    @Column(name = "completed_survey", nullable = false)
//    private int completedSurvey;
//
//    @Column(name = "failed_survey", nullable = false)
//    private int failedSurvey;
//
//    @Column(name = "error_question", nullable = false)
//    private int errorQuestion;
//
//    @OneToOne
//    @JoinColumn(name = "form_id", updatable = false, nullable = false)
//    private Form form;
}
