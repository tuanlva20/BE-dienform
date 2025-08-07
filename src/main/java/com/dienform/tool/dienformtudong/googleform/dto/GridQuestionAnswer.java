package com.dienform.tool.dienformtudong.googleform.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing grid question answers Supports multiple answer formats for grid questions
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GridQuestionAnswer {

  /**
   * Inner class for representing row answers
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class RowAnswer {
    private String rowLabel;
    private Object selectedOptions; // Can be String or List<String>
  }

  /**
   * Create GridQuestionAnswer from raw text
   */
  public static GridQuestionAnswer fromRawText(UUID questionId, String questionTitle,
      String questionType, String rawAnswerText) {
    return GridQuestionAnswer.builder().questionId(questionId).questionTitle(questionTitle)
        .questionType(questionType).rawAnswerText(rawAnswerText).build();
  }

  /**
   * Create GridQuestionAnswer with row answers
   */
  public static GridQuestionAnswer withRowAnswers(UUID questionId, String questionTitle,
      String questionType, Map<String, Object> rowAnswers) {
    return GridQuestionAnswer.builder().questionId(questionId).questionTitle(questionTitle)
        .questionType(questionType).rowAnswers(rowAnswers).build();
  }

  /**
   * Create GridQuestionAnswer with row answer list
   */
  public static GridQuestionAnswer withRowAnswerList(UUID questionId, String questionTitle,
      String questionType, List<RowAnswer> rowAnswerList) {
    return GridQuestionAnswer.builder().questionId(questionId).questionTitle(questionTitle)
        .questionType(questionType).rowAnswerList(rowAnswerList).build();
  }

  private UUID questionId;
  private String questionTitle;

  private String questionType;

  private String rawAnswerText;

  private Map<String, Object> rowAnswers;

  private List<RowAnswer> rowAnswerList;
}
