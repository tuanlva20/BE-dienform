package com.dienform.tool.dienformtudong.question.entity;

import com.dienform.tool.dienformtudong.form.entity.Form;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.CreatedDate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "question")
public class Question {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Lob
  @Column(name = "title")
  private String title;

  @Lob
  @Column(name = "description")
  private String description;

  @Column(name = "type")
  private String type;

  @Column(name = "required")
  private Boolean required = false;

  @Column(name = "position")
  private Integer position;

  @Column(name = "created_at")
  @CreatedDate
  private LocalDateTime createdAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "form_id")
  private Form form;

  @OneToMany(mappedBy = "question", fetch = FetchType.LAZY)
  private List<QuestionOption> options = new ArrayList<>();

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
