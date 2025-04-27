package com.dienform.tool.dienformtudong.answerdistribution.entity;

import com.dienform.common.entity.AuditEntity;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.CreatedDate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "answer_distribution")
public class AnswerDistribution extends AuditEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "percentage", nullable = false)
  private Integer percentage;

  @Column(name = "count")
  private Integer count;

  @Lob
  @Column(name = "value_string")
  private String valueString;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "fill_request_id", nullable = false)
  private FillRequest fillRequest;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "question_id", nullable = false)
  private Question question;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "option_id", nullable = false)
  private QuestionOption option;
}
