package com.dienform.tool.dienformtudong.question.dto.request;

import lombok.Data;
import lombok.Value;
import lombok.experimental.FieldDefaults;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for {@link com.dienform.tool.dienformtudong.question.entity.QuestionOption}
 */
@Data
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class QuestionOptionRequest implements Serializable {
  UUID id;
  String text;
  String value;
  Integer position;
  LocalDateTime createdAt;
}
