package com.dienform.tool.dienformtudong.aisuggestion.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AISuggestionRequest;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AnswerAttributesRequest;
import com.dienform.tool.dienformtudong.aisuggestion.dto.response.AnswerAttributesResponse;
import com.dienform.tool.dienformtudong.aisuggestion.exception.AISuggestionException;
import com.dienform.tool.dienformtudong.aisuggestion.service.AISuggestionService;
import com.dienform.tool.dienformtudong.aisuggestion.service.validator.AISuggestionValidator;
import com.dienform.tool.dienformtudong.aisuggestion.util.AIServiceClient;
import com.dienform.tool.dienformtudong.form.dto.response.FormDetailResponse;
import com.dienform.tool.dienformtudong.form.service.FormService;
import com.dienform.tool.dienformtudong.question.dto.response.QuestionOptionResponse;
import com.dienform.tool.dienformtudong.question.dto.response.QuestionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of AI Suggestion Service Handles answer attributes generation and request
 * validation
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AISuggestionServiceImpl implements AISuggestionService {

  private final AIServiceClient aiServiceClient;
  private final AISuggestionValidator validator;
  private final FormService formService;

  @Override
  @Transactional(readOnly = true)
  public Map<String, Object> validateRequest(AISuggestionRequest request) {
    log.debug("Validating AI suggestion request for form: {}", request.getFormId());

    Map<String, Object> validation = new HashMap<>();
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    try {
      Map<String, Object> formData = getFormData(request.getFormId());
      validator.validateRequest(request, formData);
      validation.put("isValid", true);
    } catch (Exception e) {
      errors.add(e.getMessage());
      validation.put("isValid", false);
    }

    // Add warnings for optimization
    if (request.getSampleCount() != null && request.getSampleCount() > 100) {
      warnings.add("Large sample count may result in longer processing time");
    }

    validation.put("errors", errors);
    validation.put("warnings", warnings);

    return validation;
  }

  @Override
  public AnswerAttributesResponse generateAnswerAttributes(AnswerAttributesRequest request) {
    log.info("Generating answer attributes for form: {}, samples: {}", request.getFormId(),
        request.getSampleCount());

    try {
      // Get form data from form service
      Map<String, Object> formData = getFormData(request.getFormId());

      // Validate request
      validator.validateAnswerAttributesRequest(request, formData);

      // Generate answer attributes using AI
      AnswerAttributesResponse response =
          aiServiceClient.generateAnswerAttributes(request, formData);

      // Normalize percentages for all questions to ensure they add up to 100%
      log.info("Starting percentage normalization for all questions in form: {}",
          request.getFormId());
      normalizeAllQuestionPercentages(response);
      log.info("Completed percentage normalization for all questions in form: {}",
          request.getFormId());

      // Normalize option IDs to current DB state (map by value/text)
      log.info("Normalizing option IDs to match current DB state for form: {}",
          request.getFormId());
      normalizeOptionIdsToDb(response, formData);
      log.info("Completed option ID normalization for form: {}", request.getFormId());

      log.info("Successfully generated answer attributes for form: {}", request.getFormId());

      return response;

    } catch (Exception e) {
      log.error("Error generating answer attributes for form {}: {}", request.getFormId(),
          e.getMessage(), e);
      throw new AISuggestionException("Failed to generate answer attributes: " + e.getMessage());
    }
  }

  public Map<String, Object> getFormData(String formId) {
    try {
      // Get form data from FormService
      UUID formUuid = UUID.fromString(formId);
      FormDetailResponse form = formService.getFormById(formUuid);

      Map<String, Object> formData = new HashMap<>();
      formData.put("id", form.getId().toString());
      formData.put("name", form.getName());
      formData.put("editLink", form.getEditLink());
      formData.put("status", form.getStatus());

      // Convert questions to map format
      List<Map<String, Object>> questionsList = new ArrayList<>();
      if (form.getQuestions() != null) {
        for (QuestionResponse question : form.getQuestions()) {
          Map<String, Object> questionMap = new HashMap<>();
          questionMap.put("id", question.getId().toString());
          questionMap.put("text", question.getTitle()); // Map title to text for validator
                                                        // compatibility
          questionMap.put("title", question.getTitle());
          questionMap.put("description", question.getDescription());
          questionMap.put("type", question.getType());
          questionMap.put("required", question.isRequired());
          questionMap.put("position", question.getPosition());
          questionMap.put("additionalData", question.getAdditionalData());

          // Convert options to map format
          List<Map<String, Object>> optionsList = new ArrayList<>();
          List<String> optionsTextList = new ArrayList<>(); // For validator compatibility
          if (question.getOptions() != null) {
            for (QuestionOptionResponse option : question.getOptions()) {
              Map<String, Object> optionMap = new HashMap<>();
              optionMap.put("id", option.getId().toString());
              optionMap.put("text", option.getText());
              optionMap.put("value", option.getValue());
              optionMap.put("position", option.getPosition());
              optionsList.add(optionMap);
              optionsTextList.add(option.getText()); // Add text for validator
            }
          }
          questionMap.put("options", optionsList);
          questionMap.put("optionsText", optionsTextList); // Add text list for validator
                                                           // compatibility
          questionsList.add(questionMap);
        }
      }

      formData.put("questions", questionsList);

      // Debug log to verify form data structure
      log.info("Retrieved form data for ID {}: {} questions found", formId, questionsList.size());
      log.debug("Form data structure: {}", formData);

      return formData;
    } catch (Exception e) {
      log.error("Error retrieving form data for ID {}: {}", formId, e.getMessage(), e);
      throw new AISuggestionException("Failed to retrieve form data for ID: " + formId, e);
    }
  }

  /**
   * Normalize AI-suggested optionIds to actual DB option IDs using the latest form data. This
   * prevents stale/invalid UUIDs in AI response from leaking to the client.
   */
  @SuppressWarnings("unchecked")
  private void normalizeOptionIdsToDb(AnswerAttributesResponse response,
      Map<String, Object> formData) {
    if (response == null || response.getQuestionAnswerAttributes() == null || formData == null) {
      return;
    }

    // Build lookup: questionId -> { validIds, value->id, text->id }
    Map<String, Map<String, String>> questionIdToValueMap = new HashMap<>();
    Map<String, Map<String, String>> questionIdToTextMap = new HashMap<>();
    Map<String, java.util.Set<String>> questionIdToIdSet = new HashMap<>();

    try {
      List<Map<String, Object>> questions = (List<Map<String, Object>>) formData.get("questions");
      if (questions != null) {
        for (Map<String, Object> q : questions) {
          String qid = String.valueOf(q.get("id"));
          List<Map<String, Object>> options = (List<Map<String, Object>>) q.get("options");
          Map<String, String> valueMap = new HashMap<>();
          Map<String, String> textMap = new HashMap<>();
          java.util.Set<String> idSet = new java.util.HashSet<>();

          if (options != null) {
            for (Map<String, Object> opt : options) {
              String oid = opt.get("id") != null ? String.valueOf(opt.get("id")) : null;
              String val = opt.get("value") != null ? String.valueOf(opt.get("value")) : null;
              String txt = opt.get("text") != null ? String.valueOf(opt.get("text")) : null;
              if (oid != null) {
                idSet.add(oid);
              }
              if (val != null && !val.isBlank()) {
                valueMap.put(val, oid);
                valueMap.put(val.toLowerCase(), oid);
              }
              if (txt != null && !txt.isBlank()) {
                String norm = txt.trim().toLowerCase();
                textMap.put(norm, oid);
              }
            }
          }

          questionIdToValueMap.put(qid, valueMap);
          questionIdToTextMap.put(qid, textMap);
          questionIdToIdSet.put(qid, idSet);
        }
      }
    } catch (Exception e) {
      log.debug("Failed building option lookup maps: {}", e.getMessage());
      return;
    }

    // Remap IDs in response
    for (AnswerAttributesResponse.QuestionAnswerAttribute qa : response
        .getQuestionAnswerAttributes()) {
      String qid = qa.getQuestionId();
      if (qid == null) {
        continue;
      }
      Map<String, String> valueMap = questionIdToValueMap.getOrDefault(qid, Map.of());
      Map<String, String> textMap = questionIdToTextMap.getOrDefault(qid, Map.of());
      java.util.Set<String> validIds = questionIdToIdSet.getOrDefault(qid, java.util.Set.of());

      // Regular options
      if (qa.getOptionDistributions() != null) {
        for (AnswerAttributesResponse.OptionDistribution dist : qa.getOptionDistributions()) {
          if (dist == null)
            continue;
          String oid = dist.getOptionId();
          String otext = dist.getOptionText();
          String ovalue = dist.getOptionValue();

          // If already a valid ID for this question, keep
          if (oid != null && validIds.contains(oid)) {
            continue;
          }

          // Prefer mapping by value, then by text
          String mapped = null;
          if (ovalue != null && !ovalue.isBlank()) {
            mapped = valueMap.getOrDefault(ovalue, valueMap.get(ovalue.toLowerCase()));
          }
          if (mapped == null && otext != null && !otext.isBlank()) {
            mapped = textMap.get(otext.trim().toLowerCase());
          }

          if (mapped != null) {
            dist.setOptionId(mapped);
          } else {
            // As a last resort, clear invalid id to avoid misleading clients
            if (oid != null && !validIds.contains(oid)) {
              log.debug("Clearing unknown optionId '{}' for question '{}' (title: {})", oid, qid,
                  qa.getQuestionTitle());
              dist.setOptionId(null);
            }
          }
        }
      }

      // Grid options
      if (qa.getGridRowDistributions() != null) {
        for (AnswerAttributesResponse.GridRowDistribution row : qa.getGridRowDistributions()) {
          if (row == null || row.getColumnDistributions() == null)
            continue;
          for (AnswerAttributesResponse.OptionDistribution col : row.getColumnDistributions()) {
            if (col == null)
              continue;
            String oid = col.getOptionId();
            String otext = col.getOptionText();
            String ovalue = col.getOptionValue();

            if (oid != null && validIds.contains(oid)) {
              continue;
            }

            String mapped = null;
            if (ovalue != null && !ovalue.isBlank()) {
              mapped = valueMap.getOrDefault(ovalue, valueMap.get(ovalue.toLowerCase()));
            }
            if (mapped == null && otext != null && !otext.isBlank()) {
              mapped = textMap.get(otext.trim().toLowerCase());
            }

            if (mapped != null) {
              col.setOptionId(mapped);
            } else {
              if (oid != null && !validIds.contains(oid)) {
                log.debug("Clearing unknown grid optionId '{}' for question '{}' (row: {})", oid,
                    qid, row.getRowLabel());
                col.setOptionId(null);
              }
            }
          }
        }
      }
    }
  }

  /**
   * Normalizes percentages for all questions to ensure they add up to 100% This is a fallback
   * mechanism when AI doesn't properly handle question percentages
   */
  private void normalizeAllQuestionPercentages(AnswerAttributesResponse response) {
    if (response == null || response.getQuestionAnswerAttributes() == null) {
      log.debug("Response or questionAnswerAttributes is null, skipping normalization");
      return;
    }

    log.debug("Found {} questions to check for percentage normalization",
        response.getQuestionAnswerAttributes().size());

    for (AnswerAttributesResponse.QuestionAnswerAttribute questionAttr : response
        .getQuestionAnswerAttributes()) {
      log.debug("Checking question: {} (type: {})", questionAttr.getQuestionTitle(),
          questionAttr.getQuestionType());

      if (isGridQuestion(questionAttr.getQuestionType())) {
        log.info("Found grid question: {} (type: {})", questionAttr.getQuestionTitle(),
            questionAttr.getQuestionType());

        // Handle new grid structure with row-based distributions
        if (questionAttr.getGridRowDistributions() != null
            && !questionAttr.getGridRowDistributions().isEmpty()) {
          log.info("Normalizing {} grid row distributions for grid question: {}",
              questionAttr.getGridRowDistributions().size(), questionAttr.getQuestionTitle());
          normalizeGridRowDistributions(questionAttr);
        } else if (questionAttr.getOptionDistributions() != null) {
          // Fallback to old structure
          log.info("Normalizing {} option distributions for grid question: {}",
              questionAttr.getOptionDistributions().size(), questionAttr.getQuestionTitle());
          normalizeQuestionPercentages(questionAttr);
        } else {
          log.warn("Grid question has no distributions: {}", questionAttr.getQuestionTitle());
        }
      } else if (hasPercentageDistributions(questionAttr)) {
        // Normalize regular questions (radio, checkbox, select) with percentage distributions
        log.info("Found regular question with percentage distributions: {} (type: {})",
            questionAttr.getQuestionTitle(), questionAttr.getQuestionType());
        normalizeQuestionPercentages(questionAttr);
      }
    }
  }

  /**
   * Checks if a question type is a grid question
   */
  private boolean isGridQuestion(String questionType) {
    return "multiple_choice_grid".equals(questionType) || "checkbox_grid".equals(questionType);
  }

  /**
   * Checks if a question has percentage distributions that need normalization
   */
  private boolean hasPercentageDistributions(
      AnswerAttributesResponse.QuestionAnswerAttribute questionAttr) {
    if (questionAttr.getOptionDistributions() == null
        || questionAttr.getOptionDistributions().isEmpty()) {
      return false;
    }

    String questionType = questionAttr.getQuestionType();
    // Text questions don't have percentage distributions
    if ("text".equals(questionType) || "date".equals(questionType) || "time".equals(questionType)) {
      return false;
    }

    // Check if the question has options with percentage values
    return questionAttr.getOptionDistributions().stream()
        .anyMatch(dist -> dist.getPercentage() != null && dist.getPercentage() > 0);
  }

  /**
   * Normalizes percentages for grid row distributions to ensure each row's column percentages add
   * up to 100%
   */
  private void normalizeGridRowDistributions(
      AnswerAttributesResponse.QuestionAnswerAttribute questionAttr) {
    List<AnswerAttributesResponse.GridRowDistribution> gridRowDistributions =
        questionAttr.getGridRowDistributions();

    if (gridRowDistributions == null || gridRowDistributions.isEmpty()) {
      return;
    }

    for (AnswerAttributesResponse.GridRowDistribution rowDistribution : gridRowDistributions) {
      List<AnswerAttributesResponse.OptionDistribution> columnDistributions =
          rowDistribution.getColumnDistributions();

      if (columnDistributions == null || columnDistributions.isEmpty()) {
        continue;
      }

      // Calculate total percentage for this row
      double totalPercentage = columnDistributions.stream()
          .mapToDouble(AnswerAttributesResponse.OptionDistribution::getPercentage).sum();

      // If total is not 100%, normalize the percentages
      if (Math.abs(totalPercentage - 100.0) > 0.01) { // Allow small floating point differences
        log.debug(
            "Normalizing percentages for row '{}' in question '{}': total was {}, normalizing to 100%",
            rowDistribution.getRowLabel(), questionAttr.getQuestionTitle(), totalPercentage);

        // Normalize each percentage for this row
        for (AnswerAttributesResponse.OptionDistribution distribution : columnDistributions) {
          double normalizedPercentage = (distribution.getPercentage() / totalPercentage) * 100.0;
          distribution.setPercentage((int) Math.round(normalizedPercentage)); // Round to integer
        }

        log.debug("Normalized percentages for row '{}' in question '{}': {}",
            rowDistribution.getRowLabel(), questionAttr.getQuestionTitle(),
            columnDistributions.stream()
                .mapToDouble(AnswerAttributesResponse.OptionDistribution::getPercentage).sum());
      }
    }
  }

  /**
   * Normalizes percentages for a single question to ensure they add up to 100% For grid questions,
   * filters out row headers and only normalizes actual choice percentages
   */
  private void normalizeQuestionPercentages(
      AnswerAttributesResponse.QuestionAnswerAttribute questionAttr) {
    List<AnswerAttributesResponse.OptionDistribution> distributions =
        questionAttr.getOptionDistributions();
    if (distributions == null || distributions.isEmpty()) {
      return;
    }

    // For grid questions, filter out row headers and only normalize actual choices
    List<AnswerAttributesResponse.OptionDistribution> actualChoices = distributions;
    if (isGridQuestion(questionAttr.getQuestionType())) {
      actualChoices = distributions.stream()
          .filter(dist -> !isRowHeader(dist.getOptionText(), dist.getOptionValue()))
          .collect(Collectors.toList());

      log.debug("Filtered {} actual choices from {} total options for grid question: {}",
          actualChoices.size(), distributions.size(), questionAttr.getQuestionTitle());
    }

    if (actualChoices.isEmpty()) {
      return;
    }

    // Calculate total percentage of actual choices
    double totalPercentage = actualChoices.stream()
        .mapToDouble(AnswerAttributesResponse.OptionDistribution::getPercentage).sum();

    // If total is not 100%, normalize the percentages
    if (Math.abs(totalPercentage - 100.0) > 0.01) { // Allow small floating point differences
      log.debug("Normalizing percentages for question {}: total was {}, normalizing to 100%",
          questionAttr.getQuestionTitle(), totalPercentage);

      // Normalize each percentage of actual choices
      for (AnswerAttributesResponse.OptionDistribution distribution : actualChoices) {
        double normalizedPercentage = (distribution.getPercentage() / totalPercentage) * 100.0;
        distribution.setPercentage((int) Math.round(normalizedPercentage)); // Round to integer
      }

      log.debug("Normalized percentages for question {}: {}", questionAttr.getQuestionTitle(),
          actualChoices.stream()
              .mapToDouble(AnswerAttributesResponse.OptionDistribution::getPercentage).sum());
    }
  }

  /**
   * Checks if an option is a row header (should not be counted in percentage calculation)
   */
  private boolean isRowHeader(String optionText, String optionValue) {
    // Row headers typically have values like "row_0", "row_1", etc.
    if (optionValue != null && optionValue.startsWith("row_")) {
      return true;
    }

    // Common row header patterns in Vietnamese
    String text = optionText != null ? optionText.toLowerCase() : "";
    return text.contains("ngày") || text.contains("buổi") || text.contains("thời gian")
        || text.contains("sáng") || text.contains("chiều") || text.contains("tối");
  }
}
