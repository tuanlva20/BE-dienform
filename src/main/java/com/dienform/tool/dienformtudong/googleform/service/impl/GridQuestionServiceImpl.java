package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.googleform.dto.GridQuestionAnswer;
import com.dienform.tool.dienformtudong.googleform.service.GridQuestionService;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of GridQuestionService Handles parsing, validation, and conversion of grid
 * question answers
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GridQuestionServiceImpl implements GridQuestionService {

  private static final Pattern GRID_OPTION_PATTERN = Pattern.compile("(.+):(.+)");
  private static final Pattern MULTIPLE_OPTIONS_PATTERN = Pattern.compile("(.+?),(.+)");

  private final QuestionRepository questionRepository;

  @Override
  public GridQuestionAnswer parseGridAnswer(Question question, String rawAnswerText) {
    if (rawAnswerText == null || rawAnswerText.trim().isEmpty()) {
      return GridQuestionAnswer.fromRawText(question.getId(), question.getTitle(),
          question.getType(), rawAnswerText);
    }

    String normalizedText = rawAnswerText.trim();

    // Support multiple row entries separated by ';' (e.g., "Row1:optA,optB;Row2:1|3")
    if (normalizedText.contains(";")) {
      Map<String, Object> rowAnswers = new HashMap<>();
      String[] rowParts = normalizedText.split(";");
      for (String rowPart : rowParts) {
        String trimmedPart = rowPart.trim();
        if (trimmedPart.isEmpty()) {
          continue;
        }
        Matcher multiRowMatcher = GRID_OPTION_PATTERN.matcher(trimmedPart);
        if (multiRowMatcher.matches()) {
          String row = multiRowMatcher.group(1).trim();
          String optionsPart = multiRowMatcher.group(2).trim();
          if (optionsPart.contains(",") || optionsPart.contains("|")) {
            List<String> options = parseMultipleOptions(optionsPart);
            rowAnswers.put(row, options);
          } else {
            rowAnswers.put(row, optionsPart);
          }
        }
      }
      if (!rowAnswers.isEmpty()) {
        return GridQuestionAnswer.withRowAnswers(question.getId(), question.getTitle(),
            question.getType(), rowAnswers);
      }
    }

    // Check if it contains row information (format: "row:option")
    Matcher gridMatcher = GRID_OPTION_PATTERN.matcher(normalizedText);
    if (gridMatcher.matches()) {
      String row = gridMatcher.group(1).trim();
      String optionsPart = gridMatcher.group(2).trim();

      // Check if options part contains multiple options
      if (optionsPart.contains(",") || optionsPart.contains("|")) {
        List<String> options = parseMultipleOptions(optionsPart);
        Map<String, Object> rowAnswers = new HashMap<>();
        rowAnswers.put(row, options);

        return GridQuestionAnswer.withRowAnswers(question.getId(), question.getTitle(),
            question.getType(), rowAnswers);
      } else {
        Map<String, Object> rowAnswers = new HashMap<>();
        rowAnswers.put(row, optionsPart);

        return GridQuestionAnswer.withRowAnswers(question.getId(), question.getTitle(),
            question.getType(), rowAnswers);
      }
    }

    // No row specified, check if it contains multiple options
    if (normalizedText.contains(",") || normalizedText.contains("|")) {
      List<String> options = parseMultipleOptions(normalizedText);
      Map<String, Object> rowAnswers = new HashMap<>();
      rowAnswers.put("all", options); // Use "all" to indicate all rows

      return GridQuestionAnswer.withRowAnswers(question.getId(), question.getTitle(),
          question.getType(), rowAnswers);
    }

    // Single option for all rows
    Map<String, Object> rowAnswers = new HashMap<>();
    rowAnswers.put("all", normalizedText);

    return GridQuestionAnswer.withRowAnswers(question.getId(), question.getTitle(),
        question.getType(), rowAnswers);
  }

  @Override
  public boolean validateGridAnswer(Question question, GridQuestionAnswer gridAnswer) {
    if (question == null || gridAnswer == null) {
      return false;
    }

    // Check if question type is supported
    if (!isGridQuestionType(question.getType())) {
      log.warn("Question type '{}' is not a grid question type", question.getType());
      return false;
    }

    // If we have row answers, validate them
    if (gridAnswer.getRowAnswers() != null && !gridAnswer.getRowAnswers().isEmpty()) {
      return validateRowAnswers(question, gridAnswer.getRowAnswers());
    }

    // If we have row answer list, validate them
    if (gridAnswer.getRowAnswerList() != null && !gridAnswer.getRowAnswerList().isEmpty()) {
      return validateRowAnswerList(question, gridAnswer.getRowAnswerList());
    }

    // If we only have raw text, it's valid (will be parsed later)
    if (gridAnswer.getRawAnswerText() != null) {
      return true;
    }

    return false;
  }

  @Override
  public List<String> getRowLabels(Question question) {
    List<String> rowLabels = new ArrayList<>();

    try {
      if (question.getOptions() != null) {
        for (QuestionOption option : question.getOptions()) {
          if (option.isRow()) {
            rowLabels.add(option.getText());
          }
        }
      }
    } catch (org.hibernate.LazyInitializationException e) {
      log.debug("Lazy initialization exception for question options, reloading question: {}",
          question.getId());
      try {
        Question reloadedQuestion =
            questionRepository.findWithOptionsById(question.getId()).orElse(question);
        if (reloadedQuestion.getOptions() != null) {
          for (QuestionOption option : reloadedQuestion.getOptions()) {
            if (option.isRow()) {
              rowLabels.add(option.getText());
            }
          }
        }
      } catch (Exception ex) {
        log.warn("Failed to reload question with options: {}", question.getId(), ex);
      }
    }

    return rowLabels;
  }

  @Override
  public List<String> getColumnOptions(Question question) {
    List<String> columnOptions = new ArrayList<>();

    try {
      if (question.getOptions() != null) {
        for (QuestionOption option : question.getOptions()) {
          if (!option.isRow()) {
            columnOptions.add(option.getText());
          }
        }
      }
    } catch (org.hibernate.LazyInitializationException e) {
      log.debug("Lazy initialization exception for question options, reloading question: {}",
          question.getId());
      try {
        Question reloadedQuestion =
            questionRepository.findWithOptionsById(question.getId()).orElse(question);
        if (reloadedQuestion.getOptions() != null) {
          for (QuestionOption option : reloadedQuestion.getOptions()) {
            if (!option.isRow()) {
              columnOptions.add(option.getText());
            }
          }
        }
      } catch (Exception ex) {
        log.warn("Failed to reload question with options: {}", question.getId(), ex);
      }
    }

    return columnOptions;
  }

  @Override
  public Map<String, Object> convertToRowMap(GridQuestionAnswer gridAnswer) {
    if (gridAnswer.getRowAnswers() != null) {
      return new HashMap<>(gridAnswer.getRowAnswers());
    }

    if (gridAnswer.getRowAnswerList() != null) {
      Map<String, Object> rowMap = new HashMap<>();
      for (GridQuestionAnswer.RowAnswer rowAnswer : gridAnswer.getRowAnswerList()) {
        rowMap.put(rowAnswer.getRowLabel(), rowAnswer.getSelectedOptions());
      }
      return rowMap;
    }

    return new HashMap<>();
  }

  @Override
  public List<GridQuestionAnswer.RowAnswer> convertToRowList(GridQuestionAnswer gridAnswer) {
    if (gridAnswer.getRowAnswerList() != null) {
      return new ArrayList<>(gridAnswer.getRowAnswerList());
    }

    if (gridAnswer.getRowAnswers() != null) {
      List<GridQuestionAnswer.RowAnswer> rowList = new ArrayList<>();
      for (Map.Entry<String, Object> entry : gridAnswer.getRowAnswers().entrySet()) {
        GridQuestionAnswer.RowAnswer rowAnswer = GridQuestionAnswer.RowAnswer.builder()
            .rowLabel(entry.getKey()).selectedOptions(entry.getValue()).build();
        rowList.add(rowAnswer);
      }
      return rowList;
    }

    return new ArrayList<>();
  }

  /**
   * Check if the question type is a grid question type
   */
  private boolean isGridQuestionType(String questionType) {
    return "multiple_choice_grid".equals(questionType) || "checkbox_grid".equals(questionType);
  }

  /**
   * Parse multiple options separated by commas
   */
  private List<String> parseMultipleOptions(String optionsText) {
    List<String> options = new ArrayList<>();
    String[] parts = optionsText.split("[,|]");
    for (String part : parts) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        options.add(trimmed);
      }
    }
    return options;
  }

  /**
   * Validate row answers against question structure
   */
  private boolean validateRowAnswers(Question question, Map<String, Object> rowAnswers) {
    List<String> availableRowLabels = getRowLabels(question);
    List<String> availableColumnOptions = getColumnOptions(question);

    for (Map.Entry<String, Object> entry : rowAnswers.entrySet()) {
      String rowLabel = entry.getKey();
      Object selectedOptions = entry.getValue();

      // Skip "all" rows validation for now
      if ("all".equals(rowLabel)) {
        continue;
      }

      // Validate row label exists
      if (!availableRowLabels.contains(rowLabel)) {
        log.warn("Row label '{}' not found in available rows: {}", rowLabel, availableRowLabels);
        return false;
      }

      // Validate selected options
      if (selectedOptions instanceof String) {
        String option = (String) selectedOptions;
        if (!availableColumnOptions.contains(option)) {
          log.warn("Column option '{}' not found in available options: {}", option,
              availableColumnOptions);
          return false;
        }
      } else if (selectedOptions instanceof List) {
        @SuppressWarnings("unchecked")
        List<String> options = (List<String>) selectedOptions;
        for (String option : options) {
          if (!availableColumnOptions.contains(option)) {
            log.warn("Column option '{}' not found in available options: {}", option,
                availableColumnOptions);
            return false;
          }
        }
      }
    }

    return true;
  }

  /**
   * Validate row answer list against question structure
   */
  private boolean validateRowAnswerList(Question question,
      List<GridQuestionAnswer.RowAnswer> rowAnswerList) {
    List<String> availableRowLabels = getRowLabels(question);
    List<String> availableColumnOptions = getColumnOptions(question);

    for (GridQuestionAnswer.RowAnswer rowAnswer : rowAnswerList) {
      String rowLabel = rowAnswer.getRowLabel();
      Object selectedOptions = rowAnswer.getSelectedOptions();

      // Validate row label exists
      if (!availableRowLabels.contains(rowLabel)) {
        log.warn("Row label '{}' not found in available rows: {}", rowLabel, availableRowLabels);
        return false;
      }

      // Validate selected options
      if (selectedOptions instanceof String) {
        String option = (String) selectedOptions;
        if (!availableColumnOptions.contains(option)) {
          log.warn("Column option '{}' not found in available options: {}", option,
              availableColumnOptions);
          return false;
        }
      } else if (selectedOptions instanceof List) {
        @SuppressWarnings("unchecked")
        List<String> options = (List<String>) selectedOptions;
        for (String option : options) {
          if (!availableColumnOptions.contains(option)) {
            log.warn("Column option '{}' not found in available options: {}", option,
                availableColumnOptions);
            return false;
          }
        }
      }
    }

    return true;
  }
}
