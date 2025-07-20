package com.dienform.tool.dienformtudong.question.entity;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.dienform.common.entity.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "question_option")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionOption extends AuditEntity implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(name = "option_text", nullable = false)
  private String text;

  @Column(name = "value")
  private String value;

  @Column(name = "position")
  private Integer position;

  @Column(name = "is_row")
  private boolean isRow;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_option_id")
  private QuestionOption parentOption;

  @Builder.Default
  @OneToMany(mappedBy = "parentOption", fetch = FetchType.LAZY)
  private List<QuestionOption> subOptions = new ArrayList<>();

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "question_id")
  private Question question;

  // Copy constructor
  public QuestionOption(QuestionOption source) {
    this.setId(source.getId());
    this.text = source.getText();
    this.value = source.getValue();
    this.position = source.getPosition();
    this.isRow = source.isRow();
    // Don't copy parent option to avoid circular references
    this.parentOption = null;
    // Don't copy question to avoid circular references
    this.question = null;
    // Sub options will be set separately to avoid circular references
    this.subOptions = new ArrayList<>();
  }
}
