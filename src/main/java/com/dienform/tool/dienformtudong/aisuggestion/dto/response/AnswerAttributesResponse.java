package com.dienform.tool.dienformtudong.aisuggestion.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for answer attributes response. Contains distribution percentages and sample answers for each
 * question.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnswerAttributesResponse {

  /**
   * DTO for question answer attributes.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class QuestionAnswerAttribute {
    private String questionId;
    private String questionTitle;
    private String questionType;
    private Boolean isRequired;
    private List<OptionDistribution> optionDistributions;
    private List<String> sampleAnswers;
    private String description;

    private List<GridRowDistribution> gridRowDistributions;
  }

  /**
   * DTO for grid row distribution.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class GridRowDistribution {

    /** Row ID. */
    private String rowId;

    /** Row label. */
    private String rowLabel;

    /** Column distributions for this row. */
    private List<OptionDistribution> columnDistributions;

    /** Description of the row. */
    private String description;
  }

  /**
   * DTO for option distribution.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class OptionDistribution {

    private String optionId;
    private String optionText;
    private String optionValue;
    private Integer percentage;
    private List<String> sampleValues;
    private String description;
  }

  private String formId;
  private String formTitle;
  private Integer sampleCount;
  private List<QuestionAnswerAttribute> questionAnswerAttributes;
  private String generatedAt;
  private String requestId;
}

