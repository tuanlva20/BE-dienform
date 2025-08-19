package com.dienform.tool.dienformtudong.googleform.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing information about a question in a section
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionInfo {
  private com.dienform.tool.dienformtudong.question.entity.Question questionEntity;
  private UUID questionId;
  private String title;
  private String type;
  private Boolean required;
  private Integer position;
}

