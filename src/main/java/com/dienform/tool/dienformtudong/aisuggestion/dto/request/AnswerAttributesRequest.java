package com.dienform.tool.dienformtudong.aisuggestion.dto.request;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for requesting answer attributes generation Used for the new /answer-attributes endpoint
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnswerAttributesRequest {

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Requirements {

    @Valid
    private StatisticalRequirements statisticalRequirements;

    @Valid
    private List<RelationshipRequirement> relationships;

    @Valid
    private List<DistributionRequirement> distributionRequirements;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class StatisticalRequirements {

    @Builder.Default
    private Boolean includeRealisticBehavior = true;

    @Builder.Default
    private String language = "vi";

    @Builder.Default
    private String responseFormat = "json";

    private Integer maxTokens;

    private Double temperature;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class RelationshipRequirement {

    @NotBlank(message = "Source question ID is required")
    private String sourceQuestionId;

    @NotBlank(message = "Target question ID is required")
    private String targetQuestionId;

    @NotBlank(message = "Relationship type is required")
    private String relationshipType;

    @Builder.Default
    private Double correlationStrength = 0.8;

    @Builder.Default
    private Double exceptionRate = 0.1;

    private String description;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class DistributionRequirement {

    @NotBlank(message = "Question ID is required")
    private String questionId;

    @NotBlank(message = "Question title is required")
    private String questionTitle;

    @NotBlank(message = "Question type is required")
    private String questionType;

    @Valid
    @NotNull(message = "Options are required")
    private List<OptionRequirement> options;

    @Builder.Default
    private Boolean isRequired = true;

    private String description;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class OptionRequirement {

    @NotBlank(message = "Option ID is required")
    private String optionId;

    @NotBlank(message = "Option text is required")
    private String optionText;

    @NotBlank(message = "Option value is required")
    private String optionValue;

    @Builder.Default
    private Integer percentage = 0;

    private String description;
  }

  @NotBlank(message = "Form ID is required")
  private String formId;

  @NotNull(message = "Sample count is required")
  @Min(value = 1, message = "Sample count must be at least 1")
  @Max(value = 1000, message = "Sample count cannot exceed 1000")
  private Integer sampleCount;

  @Valid
  @NotNull(message = "Requirements are required")
  private Requirements requirements;
}

