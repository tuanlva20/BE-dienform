package com.dienform.tool.dienformtudong.question.entity;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "question_option")
public class QuestionOption {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Lob
  @Column(name = "option_text", nullable = false)
  private String text;

  @Size(max = 255)
  @Column(name = "option_value")
  private String value;

  @Column(name = "position", nullable = false)
  private Integer position;

  @Column(name = "created_at", nullable = false)
  @CreatedDate
  private LocalDateTime createdAt;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "question_id", nullable = false, updatable = false)
  private Question question;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
