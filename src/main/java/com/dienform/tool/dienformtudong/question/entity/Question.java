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

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "form_id", nullable = false)
  private Form form;

  @Lob
  @Column(name = "title", nullable = false)
  private String title;

  @Lob
  @Column(name = "description")
  private String description;

  @Size(max = 50)
  @NotNull
  @Column(name = "type", nullable = false, length = 50)
  private String type;

  @NotNull
  @Column(name = "required", nullable = false)
  private Boolean required = false;

  @NotNull
  @Column(name = "position", nullable = false)
  private Integer position;

  @Column(name = "created_at", nullable = false)
  @CreatedDate
  private LocalDateTime createdAt;
}
