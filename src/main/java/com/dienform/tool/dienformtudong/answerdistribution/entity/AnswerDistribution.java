package com.dienform.tool.dienformtudong.answerdistribution.entity;

import java.util.UUID;
import com.dienform.common.entity.AuditEntity;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
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

  @Column(name = "value_string")
  private String valueString;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "fill_request_id", nullable = false)
  private FillRequest fillRequest;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "question_id", nullable = false)
  private Question question;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "option_id", nullable = true)
  private QuestionOption option;
}
