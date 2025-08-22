package com.dienform.tool.dienformtudong.question.dto.request;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.FieldDefaults;

/**
 * DTO for {@link com.dienform.tool.dienformtudong.question.entity.Question}
 */
@Data
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class QuestionDetailRequest implements Serializable {
  UUID id;

  @Size(max = 65535, message = "Question title cannot exceed 65,535 characters")
  String title;

  @Size(max = 65535, message = "Question description cannot exceed 65,535 characters")
  String description;
  String type;
  Boolean required;
  Integer position;
  LocalDateTime createdAt;
  List<QuestionOptionRequest> options;
}
