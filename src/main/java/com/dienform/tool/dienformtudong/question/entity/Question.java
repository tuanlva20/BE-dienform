package com.dienform.tool.dienformtudong.question.entity;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.dienform.common.entity.AuditEntity;
import com.dienform.tool.dienformtudong.form.entity.Form;
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
import jakarta.validation.constraints.Size;
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

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(name = "title", columnDefinition = "TEXT")
  @Size(max = 65535, message = "Question title cannot exceed 65,535 characters")
  private String title;

  @Column(name = "description", columnDefinition = "TEXT")
  @Size(max = 65535, message = "Question description cannot exceed 65,535 characters")
  private String description;

  @Column(name = "type")
  private String type;

  @Column(name = "required")
  private Boolean required;

  @Column(name = "position")
  private Integer position;

  @Column(name = "additional_data", columnDefinition = "JSON")
  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, String> additionalData;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "form_id")
  private Form form;

  @OneToMany(mappedBy = "question", fetch = FetchType.LAZY)
  private List<QuestionOption> options = new ArrayList<>();

  // Copy constructor
  public Question(Question source) {
    this.setId(source.getId());
    this.title = source.getTitle();
    this.description = source.getDescription();
    this.type = source.getType();
    this.required = source.getRequired();
    this.position = source.getPosition();
    this.additionalData =
        source.getAdditionalData() != null ? new HashMap<>(source.getAdditionalData()) : null;
    // Don't copy form to avoid lazy loading issues
    this.form = null;
    // Options will be set separately to avoid circular references
    this.options = new ArrayList<>();
  }

  public Boolean getRequired() {
    return required != null ? required : false;
  }

  public void setRequired(Boolean required) {
    this.required = required;
  }
}
