package com.dienform.tool.dienformtudong.question.dto.request;

import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO for {@link com.dienform.tool.dienformtudong.question.entity.Question}
 */
@Data
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class QuestionDetailRequest implements Serializable {
  UUID id;
  String title;
  String description;
  String type;
  Boolean required;
  Integer position;
  LocalDateTime createdAt;
  List<QuestionOptionRequest> options;
}
