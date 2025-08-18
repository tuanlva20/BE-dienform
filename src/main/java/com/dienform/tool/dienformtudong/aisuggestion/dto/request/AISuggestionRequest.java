package com.dienform.tool.dienformtudong.aisuggestion.dto.request;

import java.util.Map;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for AI Suggestion Request Contains basic information for validation
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AISuggestionRequest {

  @NotBlank(message = "Form ID is required")
  private String formId;

  @NotNull(message = "Sample count is required")
  @Min(value = 1, message = "Sample count must be at least 1")
  @Max(value = 1000, message = "Sample count cannot exceed 1000")
  private Integer sampleCount;

  @Size(max = 500, message = "Additional instructions cannot exceed 500 characters")
  private String additionalInstructions;

  @Builder.Default
  private String language = "vi"; // Default to Vietnamese

  @Builder.Default
  private Boolean includeRealisticBehavior = true;

  @Builder.Default
  private Boolean includeTypos = false;

  @Builder.Default
  private Boolean includeEmptyAnswers = false;

  @Min(value = 1, message = "Priority must be at least 1")
  @Max(value = 10, message = "Priority cannot exceed 10")
  @Builder.Default
  private Integer priority = 5;

  // Custom validation for business rules
  private Map<String, Object> customValidationRules;

  // Metadata for tracking and analytics
  private Map<String, String> metadata;
}
