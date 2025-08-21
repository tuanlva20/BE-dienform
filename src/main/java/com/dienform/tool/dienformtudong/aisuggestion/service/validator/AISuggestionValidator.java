package com.dienform.tool.dienformtudong.aisuggestion.service.validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AISuggestionRequest;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AnswerAttributesRequest;
import com.dienform.tool.dienformtudong.aisuggestion.exception.InvalidInputException;
import lombok.extern.slf4j.Slf4j;

/**
 * Validator for AI Suggestion requests Performs basic validation of input data and business rules
 */
@Component
@Slf4j
public class AISuggestionValidator {

  @Value("${ai.suggestion.validation.max-sample-count:1000}")
  private Integer maxSampleCount;

  @Value("${ai.suggestion.validation.max-questions-per-form:50}")
  private Integer maxQuestionsPerForm;

  @Value("${ai.suggestion.validation.min-sample-count:1}")
  private Integer minSampleCount;

  @Value("${ai.suggestion.validation.max-instruction-length:500}")
  private Integer maxInstructionLength;

  @Value("${ai.suggestion.validation.max-sample-count-for-large-forms:1000}")
  private Integer maxSampleCountForLargeForms;

  /**
   * Validate AI suggestion request
   *
   * @param request The request to validate
   * @param formData The form data for context
   * @throws InvalidInputException if validation fails
   */
  public void validateRequest(AISuggestionRequest request, Map<String, Object> formData) {
    log.debug("Starting validation for AI suggestion request: {}", request.getFormId());

    List<String> errors = new ArrayList<>();

    // Basic validation
    validateBasicRequirements(request, errors);

    // Form data validation
    validateFormData(formData, errors);

    // Business rules validation
    validateBusinessRules(request, formData, errors);

    if (!errors.isEmpty()) {
      log.warn("Validation failed for request {}: {}", request.getFormId(), errors);
      throw new InvalidInputException(errors);
    }

    log.debug("Validation completed successfully for request: {}", request.getFormId());
  }

  /**
   * Validate user quota and rate limits
   */
  public void validateUserQuota(String userId, Integer requestedSamples) {
    // This would typically check against a quota service
    // For now, implement basic validation

    if (requestedSamples > maxSampleCount) {
      throw new InvalidInputException(
          String.format("Requested sample count (%d) exceeds maximum allowed (%d)",
              requestedSamples, maxSampleCount));
    }
  }

  /**
   * Validate service availability
   */
  public void validateServiceAvailability() {
    // This could check AI service health, quota limits, etc.
    // Implementation depends on specific service monitoring
  }

  /**
   * Validate answer attributes request
   *
   * @param request The answer attributes request to validate
   * @param formData The form data for context
   * @throws InvalidInputException if validation fails
   */
  public void validateAnswerAttributesRequest(AnswerAttributesRequest request,
      Map<String, Object> formData) {
    log.debug("Starting validation for answer attributes request: {}", request.getFormId());

    List<String> errors = new ArrayList<>();

    // Basic validation
    validateBasicAnswerAttributesRequirements(request, errors);

    // Form data validation
    validateFormData(formData, errors);

    // Requirements validation
    if (request.getRequirements() != null) {
      if (request.getRequirements().getStatisticalRequirements() != null) {
        validateAnswerAttributesStatisticalRequirements(
            request.getRequirements().getStatisticalRequirements(), errors);
      }

      if (request.getRequirements().getRelationships() != null) {
        validateAnswerAttributesRelationshipRequirements(
            request.getRequirements().getRelationships(), formData, errors);
      }

      if (request.getRequirements().getDistributionRequirements() != null) {
        validateAnswerAttributesDistributionRequirements(
            request.getRequirements().getDistributionRequirements(), formData, errors);
      }
    }

    // Business rules validation
    validateAnswerAttributesBusinessRules(request, formData, errors);

    if (!errors.isEmpty()) {
      log.warn("Validation failed for answer attributes request {}: {}", request.getFormId(),
          errors);
      throw new InvalidInputException(errors);
    }

    log.debug("Validation completed successfully for answer attributes request: {}",
        request.getFormId());
  }

  private void validateBasicRequirements(AISuggestionRequest request, List<String> errors) {
    if (request.getFormId() == null || request.getFormId().trim().isEmpty()) {
      errors.add("Form ID is required");
    }

    if (request.getSampleCount() == null) {
      errors.add("Sample count is required");
    } else {
      if (request.getSampleCount() < minSampleCount) {
        errors.add("Sample count must be at least " + minSampleCount);
      }
      if (request.getSampleCount() > maxSampleCount) {
        errors.add("Sample count cannot exceed " + maxSampleCount);
      }
    }

    if (request.getPriority() != null
        && (request.getPriority() < 1 || request.getPriority() > 10)) {
      errors.add("Priority must be between 1 and 10");
    }

    if (request.getAdditionalInstructions() != null
        && request.getAdditionalInstructions().length() > maxInstructionLength) {
      errors.add("Additional instructions cannot exceed " + maxInstructionLength + " characters");
    }
  }

  private void validateFormData(Map<String, Object> formData, List<String> errors) {
    if (formData == null || formData.isEmpty()) {
      errors.add("Form data is required");
      return;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> questions = (List<Map<String, Object>>) formData.get("questions");

    if (questions == null || questions.isEmpty()) {
      errors.add("Form must contain at least one question");
      return;
    }

    if (questions.size() > maxQuestionsPerForm) {
      errors.add("Form cannot have more than " + maxQuestionsPerForm + " questions");
    }

    // Validate individual questions
    for (int i = 0; i < questions.size(); i++) {
      validateQuestion(questions.get(i), i + 1, errors);
    }
  }

  private void validateQuestion(Map<String, Object> question, int questionNumber,
      List<String> errors) {
    if (question.get("id") == null || question.get("id").toString().trim().isEmpty()) {
      errors.add("Question " + questionNumber + " must have an ID");
    }

    if (question.get("type") == null || question.get("type").toString().trim().isEmpty()) {
      errors.add("Question " + questionNumber + " must have a type");
    }

    if (question.get("text") == null || question.get("text").toString().trim().isEmpty()) {
      errors.add("Question " + questionNumber + " must have text");
    }

    String type = question.get("type").toString();
    if ("radio".equals(type) || "checkbox".equals(type)) {
      // Try to get optionsText first (for validator compatibility), then fallback to options
      @SuppressWarnings("unchecked")
      List<String> optionsText = (List<String>) question.get("optionsText");

      if (optionsText == null || optionsText.isEmpty()) {
        // Fallback to options if optionsText is not available
        Object optionsObj = question.get("options");
        if (optionsObj instanceof List) {
          @SuppressWarnings("unchecked")
          List<Object> optionsList = (List<Object>) optionsObj;
          if (optionsList.isEmpty()) {
            errors.add("Question " + questionNumber + " of type " + type + " must have options");
          }
        } else {
          errors.add("Question " + questionNumber + " of type " + type + " must have options");
        }
      }
    }
  }

  private void validateBusinessRules(AISuggestionRequest request, Map<String, Object> formData,
      List<String> errors) {
    // Sample count vs question count relationship
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> questions = (List<Map<String, Object>>) formData.get("questions");
    int questionCount = questions.size();

    if (request.getSampleCount() != null && questionCount > 0) {
      // For large forms with many questions, suggest reasonable sample limits
      if (questionCount > 1000 && request.getSampleCount() > maxSampleCountForLargeForms) {
        errors.add("For forms with more than 100 questions, sample count should not exceed "
            + maxSampleCountForLargeForms);
      }
    }

    // Language consistency
    if (request.getLanguage() != null && !request.getLanguage().equals("vi")
        && !request.getLanguage().equals("en")) {
      errors.add("Supported languages are 'vi' (Vietnamese) and 'en' (English)");
    }

    // Realistic behavior settings consistency
    if (Boolean.FALSE.equals(request.getIncludeRealisticBehavior())
        && (Boolean.TRUE.equals(request.getIncludeTypos())
            || Boolean.TRUE.equals(request.getIncludeEmptyAnswers()))) {
      errors.add("Cannot include typos or empty answers when realistic behavior is disabled");
    }
  }

  private void validateBasicAnswerAttributesRequirements(AnswerAttributesRequest request,
      List<String> errors) {
    if (request.getFormId() == null || request.getFormId().trim().isEmpty()) {
      errors.add("Form ID is required");
    }

    if (request.getSampleCount() == null) {
      errors.add("Sample count is required");
    } else if (request.getSampleCount() < minSampleCount
        || request.getSampleCount() > maxSampleCount) {
      errors.add(
          String.format("Sample count must be between %d and %d", minSampleCount, maxSampleCount));
    }

    if (request.getRequirements() == null) {
      errors.add("Requirements are required");
    }
  }

  private void validateAnswerAttributesBusinessRules(AnswerAttributesRequest request,
      Map<String, Object> formData, List<String> errors) {
    // Sample count vs question count relationship
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> questions = (List<Map<String, Object>>) formData.get("questions");
    int questionCount = questions.size();

    if (request.getSampleCount() != null && questionCount > 0) {
      // For large forms with many questions, suggest reasonable sample limits
      if (questionCount > 1000 && request.getSampleCount() > maxSampleCountForLargeForms) {
        errors.add("For forms with more than 100 questions, sample count should not exceed "
            + maxSampleCountForLargeForms);
      }
    }

    // Validate distribution requirements percentages sum to 100%
    if (request.getRequirements() != null
        && request.getRequirements().getDistributionRequirements() != null) {
      for (AnswerAttributesRequest.DistributionRequirement dist : request.getRequirements()
          .getDistributionRequirements()) {
        if (dist.getOptions() != null) {
          int totalPercentage = dist.getOptions().stream()
              .mapToInt(AnswerAttributesRequest.OptionRequirement::getPercentage).sum();

          if (totalPercentage != 100) {
            errors.add(String.format(
                "Distribution percentages for question '%s' must sum to 100%% (current: %d%%)",
                dist.getQuestionTitle(), totalPercentage));
          }
        }
      }
    }
  }

  private void validateAnswerAttributesStatisticalRequirements(
      AnswerAttributesRequest.StatisticalRequirements requirements, List<String> errors) {
    if (requirements.getLanguage() != null && !requirements.getLanguage().equals("vi")
        && !requirements.getLanguage().equals("en")) {
      errors.add("Supported languages are 'vi' (Vietnamese) and 'en' (English)");
    }

    if (requirements.getMaxTokens() != null
        && (requirements.getMaxTokens() < 100 || requirements.getMaxTokens() > 10000)) {
      errors.add("Max tokens must be between 100 and 10000");
    }

    if (requirements.getTemperature() != null
        && (requirements.getTemperature() < 0.0 || requirements.getTemperature() > 2.0)) {
      errors.add("Temperature must be between 0.0 and 2.0");
    }
  }

  private void validateAnswerAttributesRelationshipRequirements(
      List<AnswerAttributesRequest.RelationshipRequirement> relationships,
      Map<String, Object> formData, List<String> errors) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> questions = (List<Map<String, Object>>) formData.get("questions");
    List<String> questionIds = questions.stream().map(q -> q.get("id").toString()).toList();

    for (AnswerAttributesRequest.RelationshipRequirement rel : relationships) {
      if (!questionIds.contains(rel.getSourceQuestionId())) {
        errors.add("Source question ID '" + rel.getSourceQuestionId() + "' not found in form");
      }

      if (!questionIds.contains(rel.getTargetQuestionId())) {
        errors.add("Target question ID '" + rel.getTargetQuestionId() + "' not found in form");
      }

      if (rel.getCorrelationStrength() != null
          && (rel.getCorrelationStrength() < 0.0 || rel.getCorrelationStrength() > 1.0)) {
        errors.add("Correlation strength must be between 0.0 and 1.0");
      }

      if (rel.getExceptionRate() != null
          && (rel.getExceptionRate() < 0.0 || rel.getExceptionRate() > 1.0)) {
        errors.add("Exception rate must be between 0.0 and 1.0");
      }
    }
  }

  private void validateAnswerAttributesDistributionRequirements(
      List<AnswerAttributesRequest.DistributionRequirement> distributions,
      Map<String, Object> formData, List<String> errors) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> questions = (List<Map<String, Object>>) formData.get("questions");
    List<String> questionIds = questions.stream().map(q -> q.get("id").toString()).toList();

    for (AnswerAttributesRequest.DistributionRequirement dist : distributions) {
      if (!questionIds.contains(dist.getQuestionId())) {
        errors.add("Question ID '" + dist.getQuestionId() + "' not found in form");
      }

      if (dist.getOptions() == null || dist.getOptions().isEmpty()) {
        errors.add("Options are required for question '" + dist.getQuestionTitle() + "'");
      } else {
        // Validate that percentages sum to 100%
        int totalPercentage = dist.getOptions().stream()
            .mapToInt(AnswerAttributesRequest.OptionRequirement::getPercentage).sum();

        if (totalPercentage != 100) {
          errors.add(String.format(
              "Distribution percentages for question '%s' must sum to 100%% (current: %d%%)",
              dist.getQuestionTitle(), totalPercentage));
        }

        // Validate individual percentages
        for (AnswerAttributesRequest.OptionRequirement option : dist.getOptions()) {
          if (option.getPercentage() < 0 || option.getPercentage() > 100) {
            errors.add(String.format("Percentage for option '%s' must be between 0 and 100",
                option.getOptionText()));
          }
        }
      }
    }
  }
}
