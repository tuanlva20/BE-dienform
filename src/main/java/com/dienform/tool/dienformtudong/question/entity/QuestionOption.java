package com.dienform.tool.dienformtudong.question.entity;

import java.io.Serializable;
import com.dienform.common.entity.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "question_options")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionOption extends AuditEntity implements Serializable {
  private static final long serialVersionUID = 1L;

  @Column(name = "text")
  private String text;

  @Column(name = "value")
  private String value;

  @Column(name = "position")
  private Integer position;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "question_id")
  private Question question;

  // Add custom copy constructor for detached copy
  public QuestionOption(QuestionOption source) {
    this.setId(source.getId());
    this.text = source.getText();
    this.value = source.getValue();
    this.position = source.getPosition();
    // Don't copy question to avoid circular reference
    this.question = null;
  }
}
