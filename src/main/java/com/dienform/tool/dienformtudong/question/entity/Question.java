package com.dienform.tool.dienformtudong.question.entity;

import java.io.Serializable;
import java.util.List;
import com.dienform.common.entity.AuditEntity;
import com.dienform.tool.dienformtudong.form.entity.Form;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
@Table(name = "question")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Question extends AuditEntity implements Serializable {
  private static final long serialVersionUID = 1L;

  @Column(name = "title")
  private String title;

  @Column(name = "description")
  private String description;

  @Column(name = "type")
  private String type;

  @Column(name = "required")
  private Boolean required;

  @Column(name = "position")
  private Integer position;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "form_id")
  private Form form;

  @OneToMany(mappedBy = "question", fetch = FetchType.LAZY)
  private List<QuestionOption> options;

  // Add custom copy constructor for detached copy
  public Question(Question source) {
    this.setId(source.getId());
    this.title = source.getTitle();
    this.description = source.getDescription();
    this.type = source.getType();
    this.required = source.getRequired();
    this.position = source.getPosition();
    // Don't copy form to avoid lazy loading issues
    this.form = null;
  }
}
