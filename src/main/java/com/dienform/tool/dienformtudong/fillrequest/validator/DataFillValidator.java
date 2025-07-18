package com.dienform.tool.dienformtudong.fillrequest.validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DataFillValidator {

  public static class ValidationResult {
    public static ValidationResult valid() {
      return new ValidationResult(true, new ArrayList<>());
    }

    public static ValidationResult invalid(List<String> errors) {
      return new ValidationResult(false, errors);
    }

    private final boolean valid;

    private final List<String> errors;

    private ValidationResult(boolean valid, List<String> errors) {
      this.valid = valid;
      this.errors = errors;
    }

    public boolean isValid() {
      return valid;
    }

    public List<String> getErrors() {
      return errors;
    }

    public String getFirstError() {
      return errors.isEmpty() ? "" : errors.get(0);
    }
  }

  private static final Pattern EMAIL_PATTERN = Pattern
      .compile("^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$");

  private static final Pattern PHONE_PATTERN = Pattern.compile("^[+]?[0-9]{10,15}$");

  /**
   * Validate data fill request basic structure
   */
  public ValidationResult validateDataFillRequest(DataFillRequestDTO request) {
    List<String> errors = new ArrayList<>();

    // Validate form ID format
    try {
      java.util.UUID.fromString(request.getFormId());
    } catch (IllegalArgumentException e) {
      errors.add("Form ID không hợp lệ: " + request.getFormId());
    }

    // Validate sheet link
    if (!isValidGoogleSheetsUrl(request.getSheetLink())) {
      errors.add("Link Google Sheets không hợp lệ");
    }

    // Validate mappings
    if (request.getMappings() == null || request.getMappings().isEmpty()) {
      errors.add("Mappings không được để trống");
    }

    // Validate submission count
    if (request.getSubmissionCount() == null || request.getSubmissionCount() < 1) {
      errors.add("Số lượng submission phải lớn hơn 0");
    }

    // Validate date range
    if (request.getStartDate() != null && request.getEndDate() != null) {
      if (request.getStartDate().isAfter(request.getEndDate())) {
        errors.add("Ngày bắt đầu không thể sau ngày kết thúc");
      }
    }

    return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
  }

  /**
   * Validate sheet data against question types and formats
   */
  public ValidationResult validateSheetData(List<Map<String, Object>> sheetData,
      List<Question> questions, DataFillRequestDTO request) {
    List<String> errors = new ArrayList<>();

    if (sheetData == null || sheetData.isEmpty()) {
      errors.add("Dữ liệu sheet trống hoặc không thể đọc được");
      return ValidationResult.invalid(errors);
    }

    // Create mapping from column to question
    Map<String, Question> columnToQuestion = createColumnToQuestionMapping(questions, request);

    // Validate each row of data
    for (int rowIndex = 0; rowIndex < sheetData.size(); rowIndex++) {
      Map<String, Object> row = sheetData.get(rowIndex);

      for (var mapping : request.getMappings()) {
        String columnName = extractColumnName(mapping.getColumnName());
        Question question = columnToQuestion.get(mapping.getQuestionId());

        if (question == null) {
          errors.add(String.format("Không tìm thấy câu hỏi với ID: %s", mapping.getQuestionId()));
          continue;
        }

        Object cellValue = row.get(columnName);
        ValidationResult cellValidation =
            validateCellData(cellValue, question, rowIndex + 1, columnName);

        if (!cellValidation.isValid()) {
          errors.addAll(cellValidation.getErrors());
        }
      }
    }

    return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
  }

  /**
   * Convert position number to actual option value
   */
  public String convertPositionToValue(String input, List<QuestionOption> options) {
    if (isPositionNumber(input)) {
      int position = Integer.parseInt(input.trim());
      if (position >= 1 && position <= options.size()) {
        List<QuestionOption> sortedOptions = options.stream()
            .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition())).toList();
        return sortedOptions.get(position - 1).getValue();
      }
    }
    return input; // Return original value if not a valid position
  }

  /**
   * Convert multiple positions to values (for checkbox/multiselect)
   */
  public String convertMultiplePositionsToValues(String input, List<QuestionOption> options) {
    if (input == null || input.trim().isEmpty()) {
      return input;
    }

    String[] parts = input.split("\\|");
    List<String> convertedValues = new ArrayList<>();

    for (String part : parts) {
      String trimmedPart = part.trim();
      String convertedValue = convertPositionToValue(trimmedPart, options);
      convertedValues.add(convertedValue);
    }

    return String.join("|", convertedValues);
  }

  /**
   * Validate individual cell data against question type
   */
  private ValidationResult validateCellData(Object cellValue, Question question, int rowIndex,
      String columnName) {
    List<String> errors = new ArrayList<>();
    String cellStr = cellValue != null ? cellValue.toString().trim() : "";

    // Skip validation for empty cells if question is not required
    if (cellStr.isEmpty() && !Boolean.TRUE.equals(question.getRequired())) {
      return ValidationResult.valid();
    }

    // Required field validation
    if (cellStr.isEmpty() && Boolean.TRUE.equals(question.getRequired())) {
      errors.add(String.format("Dòng %d, cột %s: Câu hỏi bắt buộc không được để trống", rowIndex,
          columnName));
      return ValidationResult.invalid(errors);
    }

    // Type-specific validation
    switch (question.getType().toLowerCase()) {
      case "text":
      case "textarea":
        // No specific validation for text fields
        break;

      case "email":
        if (!EMAIL_PATTERN.matcher(cellStr).matches()) {
          errors.add(String.format("Dòng %d, cột %s: Email không hợp lệ '%s'", rowIndex, columnName,
              cellStr));
        }
        break;

      case "phone":
        String phoneStr = cellStr.replaceAll("[\\s()-]", "");
        if (!PHONE_PATTERN.matcher(phoneStr).matches()) {
          errors.add(String.format("Dòng %d, cột %s: Số điện thoại không hợp lệ '%s'", rowIndex,
              columnName, cellStr));
        }
        break;

      case "number":
        try {
          Double.parseDouble(cellStr);
        } catch (NumberFormatException e) {
          errors.add(String.format("Dòng %d, cột %s: Số không hợp lệ '%s'", rowIndex, columnName,
              cellStr));
        }
        break;

      case "date":
        // Date validation - should be in format dd/MM/yyyy or yyyy-MM-dd
        if (!isValidDateFormat(cellStr)) {
          errors.add(String.format(
              "Dòng %d, cột %s: Ngày không hợp lệ '%s'. Định dạng: dd/MM/yyyy hoặc yyyy-MM-dd",
              rowIndex, columnName, cellStr));
        }
        break;

      case "time":
        if (!isValidTimeFormat(cellStr)) {
          errors.add(String.format("Dòng %d, cột %s: Thời gian không hợp lệ '%s'. Định dạng: HH:mm",
              rowIndex, columnName, cellStr));
        }
        break;

      case "select":
      case "radio":
        ValidationResult singleChoiceValidation =
            validateSingleChoice(cellStr, question, rowIndex, columnName);
        if (!singleChoiceValidation.isValid()) {
          errors.addAll(singleChoiceValidation.getErrors());
        }
        break;

      case "multiselect":
      case "checkbox":
        ValidationResult multiChoiceValidation =
            validateMultipleChoice(cellStr, question, rowIndex, columnName);
        if (!multiChoiceValidation.isValid()) {
          errors.addAll(multiChoiceValidation.getErrors());
        }
        break;

      case "url":
        if (!isValidUrl(cellStr)) {
          errors.add(String.format("Dòng %d, cột %s: URL không hợp lệ '%s'", rowIndex, columnName,
              cellStr));
        }
        break;

      default:
        log.warn("Unknown question type: {}", question.getType());
        break;
    }

    return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
  }

  /**
   * Validate single choice questions (radio, select)
   */
  private ValidationResult validateSingleChoice(String value, Question question, int rowIndex,
      String columnName) {
    List<String> errors = new ArrayList<>();

    List<QuestionOption> options = question.getOptions().stream()
        .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition())).toList();

    List<String> validOptions = options.stream().map(QuestionOption::getValue).toList();

    // Check if value is a position number (1, 2, 3, etc.)
    if (isPositionNumber(value)) {
      int position = Integer.parseInt(value);
      if (position < 1 || position > options.size()) {
        errors.add(String.format(
            "Dòng %d, cột %s: Vị trí '%s' không hợp lệ. Các vị trí hợp lệ: 1-%d. Danh sách: %s",
            rowIndex, columnName, value, options.size(), formatOptionsWithPosition(options)));
      }
    } else {
      // Check if value matches option text exactly
      if (!validOptions.contains(value)) {
        errors.add(String.format(
            "Dòng %d, cột %s: Giá trị '%s' không có trong danh sách lựa chọn. Có thể sử dụng số thứ tự (1-%d) hoặc text chính xác. Danh sách: %s",
            rowIndex, columnName, value, options.size(), formatOptionsWithPosition(options)));
      }
    }

    return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
  }

  /**
   * Validate multiple choice questions (checkbox, multiselect)
   */
  private ValidationResult validateMultipleChoice(String value, Question question, int rowIndex,
      String columnName) {
    List<String> errors = new ArrayList<>();

    List<QuestionOption> options = question.getOptions().stream()
        .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition())).toList();

    List<String> validOptions = options.stream().map(QuestionOption::getValue).toList();

    // Split by | delimiter
    String[] selectedOptions = value.split("\\|");

    for (String option : selectedOptions) {
      String trimmedOption = option.trim();

      // Check if option is a position number (1, 2, 3, etc.)
      if (isPositionNumber(trimmedOption)) {
        int position = Integer.parseInt(trimmedOption);
        if (position < 1 || position > options.size()) {
          errors.add(String.format(
              "Dòng %d, cột %s: Vị trí '%s' không hợp lệ. Các vị trí hợp lệ: 1-%d. Danh sách: %s",
              rowIndex, columnName, trimmedOption, options.size(),
              formatOptionsWithPosition(options)));
        }
      } else {
        // Check if option matches text exactly
        if (!validOptions.contains(trimmedOption)) {
          errors.add(String.format(
              "Dòng %d, cột %s: Giá trị '%s' không có trong danh sách lựa chọn. Có thể sử dụng số thứ tự (1-%d) hoặc text chính xác. Danh sách: %s",
              rowIndex, columnName, trimmedOption, options.size(),
              formatOptionsWithPosition(options)));
        }
      }
    }

    return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
  }

  /**
   * Helper methods for format validation
   */
  private boolean isValidGoogleSheetsUrl(String url) {
    return url != null
        && url.matches("https://docs\\.google\\.com/spreadsheets/d/[a-zA-Z0-9-_]+.*");
  }

  private boolean isValidDateFormat(String date) {
    return date.matches("\\d{2}/\\d{2}/\\d{4}") || date.matches("\\d{4}-\\d{2}-\\d{2}");
  }

  private boolean isValidTimeFormat(String time) {
    return time.matches("\\d{2}:\\d{2}");
  }

  private boolean isValidUrl(String url) {
    return url.matches("^https?://.*");
  }

  private String extractColumnName(String formattedColumnName) {
    // Extract "Column Name" from "A - Column Name"
    if (formattedColumnName.contains(" - ")) {
      return formattedColumnName.substring(formattedColumnName.indexOf(" - ") + 3);
    }
    return formattedColumnName;
  }

  private Map<String, Question> createColumnToQuestionMapping(List<Question> questions,
      DataFillRequestDTO request) {
    Map<String, Question> mapping = new java.util.HashMap<>();

    for (Question question : questions) {
      mapping.put(question.getId().toString(), question);
    }

    return mapping;
  }

  /**
   * Check if value is a position number (1, 2, 3, etc.)
   */
  private boolean isPositionNumber(String value) {
    if (value == null || value.trim().isEmpty()) {
      return false;
    }

    try {
      int number = Integer.parseInt(value.trim());
      return number > 0; // Position numbers start from 1
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * Format options list with position numbers for error messages
   */
  private String formatOptionsWithPosition(List<QuestionOption> options) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < options.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(String.format("%d='%s'", i + 1, options.get(i).getValue()));
    }
    return sb.toString();
  }
}
