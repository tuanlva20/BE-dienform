package com.dienform.tool.dienformtudong.fillrequest.validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
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

  private final QuestionRepository questionRepository;

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
    } else {
      // Validate column name lengths
      for (int i = 0; i < request.getMappings().size(); i++) {
        var mapping = request.getMappings().get(i);
        if (mapping.getColumnName() != null && mapping.getColumnName().length() > 2000) {
          errors.add(
              String.format("Tên cột '%s' quá dài (tối đa 2000 ký tự). Độ dài hiện tại: %d ký tự",
                  mapping.getColumnName().substring(0,
                      Math.min(50, mapping.getColumnName().length())) + "...",
                  mapping.getColumnName().length()));
        }
      }
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

    // Validate each row of data
    for (int rowIndex = 0; rowIndex < sheetData.size(); rowIndex++) {
      Map<String, Object> row = sheetData.get(rowIndex);

      for (var mapping : request.getMappings()) {
        String columnName = extractColumnName(mapping.getColumnName());
        String mappingQuestionId = mapping.getQuestionId();

        // Support grid mapping keys in format "<questionId>:<rowLabel>"
        String baseQuestionId = mappingQuestionId;
        String explicitRowLabel = null;
        if (mappingQuestionId != null && mappingQuestionId.contains(":")) {
          String[] parts = mappingQuestionId.split(":", 2);
          baseQuestionId = parts[0];
          explicitRowLabel = parts.length > 1 ? parts[1] : null;
        }

        Question question = findQuestionById(questions, baseQuestionId);
        if (question == null) {
          errors.add(String.format("Không tìm thấy câu hỏi với ID: %s", baseQuestionId));
          continue;
        }

        // Derive row label for grid questions if needed
        String rowLabelForGrid = explicitRowLabel;
        if (rowLabelForGrid == null && isGridQuestionType(question.getType())) {
          rowLabelForGrid = extractBracketLabel(columnName);
        }

        Object cellValue = row.get(columnName);

        ValidationResult cellValidation;
        if (isGridQuestionType(question.getType())) {
          cellValidation =
              validateGridCellData(cellValue, question, rowLabelForGrid, rowIndex + 1, columnName);
        } else {
          cellValidation = validateCellData(cellValue, question, rowIndex + 1, columnName);
        }

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

      case "multiple_choice_grid":
      case "checkbox_grid":
        // Grid types are validated in validateGridCellData via caller
        break;
      default:
        log.warn("Unknown question type: {}", question.getType());
        break;
    }

    return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
  }

  /**
   * Validate grid cell data for multiple_choice_grid and checkbox_grid. Supports mapping keys in
   * format "<questionId>:<rowLabel>" and/or column labels like "<Question Title> [<Row Label>]".
   */
  private ValidationResult validateGridCellData(Object cellValue, Question question,
      String rowLabel, int rowIndex, String columnName) {
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

    if (rowLabel == null || rowLabel.trim().isEmpty()) {
      errors.add(String.format(
          "Dòng %d, cột %s: Không xác định được nhãn hàng (row) cho câu hỏi dạng lưới", rowIndex,
          columnName));
      return ValidationResult.invalid(errors);
    }

    // Build row list and find the requested row
    List<com.dienform.tool.dienformtudong.question.entity.QuestionOption> allOptions =
        getQuestionOptionsSafely(question);
    com.dienform.tool.dienformtudong.question.entity.QuestionOption matchedRow =
        allOptions.stream().filter(o -> o.isRow() && o.getText() != null
            && o.getText().trim().equalsIgnoreCase(rowLabel.trim())).findFirst().orElse(null);

    if (matchedRow == null) {
      errors.add(String.format(
          "Dòng %d, cột %s: Không tìm thấy hàng '%s' trong cấu trúc câu hỏi dạng lưới", rowIndex,
          columnName, rowLabel));
      return ValidationResult.invalid(errors);
    }

    // Determine column options at question level to avoid missing subOptions on rows
    List<com.dienform.tool.dienformtudong.question.entity.QuestionOption> sortedColumns =
        allOptions.stream().filter(o -> !o.isRow())
            .sorted((a, b) -> Integer.compare(a.getPosition() == null ? 0 : a.getPosition(),
                b.getPosition() == null ? 0 : b.getPosition()))
            .toList();

    List<String> validValues = sortedColumns.stream()
        .map(com.dienform.tool.dienformtudong.question.entity.QuestionOption::getValue).toList();

    boolean isCheckboxGrid = "checkbox_grid".equalsIgnoreCase(question.getType());
    boolean isMultipleChoiceGrid = "multiple_choice_grid".equalsIgnoreCase(question.getType());

    if (isMultipleChoiceGrid) {
      if (cellStr.contains("|")) {
        errors.add(String.format(
            "Dòng %d, cột %s: Chỉ được chọn 1 đáp án cho mỗi hàng của Multiple Choice Grid",
            rowIndex, columnName));
        return ValidationResult.invalid(errors);
      }

      // Only index allowed per requirement
      if (isPositionNumber(cellStr)) {
        int position = Integer.parseInt(cellStr);
        if (position < 1 || position > sortedColumns.size()) {
          errors.add(String.format(
              "Dòng %d, cột %s: Vị trí '%s' không hợp lệ. Các vị trí hợp lệ: 1-%d. Danh sách: %s",
              rowIndex, columnName, cellStr, sortedColumns.size(),
              formatOptionsWithPosition(sortedColumns)));
        }
      } else {
        errors.add(String.format(
            "Dòng %d, cột %s: Chỉ chấp nhận số thứ tự (1-%d) cho Multiple Choice Grid. Danh sách: %s",
            rowIndex, columnName, sortedColumns.size(), formatOptionsWithPosition(sortedColumns)));
      }

      return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
    }

    if (isCheckboxGrid) {
      String[] parts = cellStr.split("[,|]");
      for (String part : parts) {
        String value = part.trim();
        if (value.isEmpty()) {
          continue;
        }
        // Only index allowed per requirement
        if (isPositionNumber(value)) {
          int position = Integer.parseInt(value);
          if (position < 1 || position > sortedColumns.size()) {
            errors.add(String.format(
                "Dòng %d, cột %s: Vị trí '%s' không hợp lệ. Các vị trí hợp lệ: 1-%d. Danh sách: %s",
                rowIndex, columnName, value, sortedColumns.size(),
                formatOptionsWithPosition(sortedColumns)));
          }
        } else {
          errors.add(String.format(
              "Dòng %d, cột %s: Chỉ chấp nhận số thứ tự (1-%d) cho Checkbox Grid. Danh sách: %s",
              rowIndex, columnName, sortedColumns.size(),
              formatOptionsWithPosition(sortedColumns)));
        }
      }
      return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
    }

    // Unknown grid subtype - treat as valid to avoid blocking
    return ValidationResult.valid();
  }

  private boolean isGridQuestionType(String type) {
    if (type == null) {
      return false;
    }
    String lower = type.toLowerCase();
    return lower.equals("multiple_choice_grid") || lower.equals("checkbox_grid");
  }

  /**
   * Extract label inside brackets from formatted column name: "Title [Label]" -> "Label".
   */
  private String extractBracketLabel(String formattedColumnName) {
    if (formattedColumnName == null) {
      return null;
    }
    int start = formattedColumnName.lastIndexOf('[');
    int end = formattedColumnName.lastIndexOf(']');
    if (start >= 0 && end > start) {
      return formattedColumnName.substring(start + 1, end).trim();
    }
    return null;
  }

  private Question findQuestionById(List<Question> questions, String id) {
    if (id == null) {
      return null;
    }
    for (Question q : questions) {
      if (q != null && q.getId() != null && id.equalsIgnoreCase(q.getId().toString())) {
        return q;
      }
    }
    return null;
  }

  /**
   * Validate single choice questions (radio, select)
   */
  private ValidationResult validateSingleChoice(String value, Question question, int rowIndex,
      String columnName) {
    List<String> errors = new ArrayList<>();

    // Support optional "-<otherText>" suffix; we only validate the left part (indices)
    String raw = value == null ? "" : value.trim();
    String main = raw;
    String otherText = null;

    // Only split on dash if left part is numeric or __other_option__
    int dashIdx = raw.lastIndexOf('-');
    if (dashIdx > 0) {
      String left = raw.substring(0, dashIdx).trim();
      String right = raw.substring(dashIdx + 1).trim();
      if (!right.isEmpty() && (left.matches("\\d+") || "__other_option__".equalsIgnoreCase(left))) {
        main = left;
        otherText = right;
      }
    }

    List<QuestionOption> options = getQuestionOptionsSafely(question).stream()
        .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition())).toList();

    if (!isPositionNumber(main)) {
      errors.add(String.format(
          "Dòng %d, cột %s: Chỉ chấp nhận số thứ tự (1-%d) cho câu hỏi một lựa chọn. Danh sách: %s",
          rowIndex, columnName, options.size(), formatOptionsWithPosition(options)));
      return ValidationResult.invalid(errors);
    }

    int position = Integer.parseInt(main);
    if (position < 1 || position > options.size()) {
      errors.add(String.format(
          "Dòng %d, cột %s: Vị trí '%s' không hợp lệ. Các vị trí hợp lệ: 1-%d. Danh sách: %s",
          rowIndex, columnName, main, options.size(), formatOptionsWithPosition(options)));
    }

    // If other text provided, ensure the selected index corresponds to the 'Other' option
    if (otherText != null && !otherText.isEmpty()) {
      int otherIndex = -1;
      for (int i = 0; i < options.size(); i++) {
        QuestionOption opt = options.get(i);
        if (opt.getValue() != null && "__other_option__".equalsIgnoreCase(opt.getValue())) {
          otherIndex = i + 1; // 1-based
          break;
        }
      }
      if (otherIndex == -1) {
        errors.add(String.format(
            "Dòng %d, cột %s: Đã cung cấp ghi chú cho 'Khác' nhưng câu hỏi không có lựa chọn 'Khác'",
            rowIndex, columnName));
      } else if (position != otherIndex) {
        errors.add(String.format(
            "Dòng %d, cột %s: Đã cung cấp ghi chú '%s' nhưng vị trí được chọn (%d) không phải 'Khác'. Vui lòng chọn vị trí %d để nhập ghi chú cho 'Khác'",
            rowIndex, columnName, otherText, position, otherIndex));
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

    // Support optional "-<otherText>" suffix; validate left part (indices) with '|' only
    String raw = value == null ? "" : value.trim();
    String main = raw;
    String otherText = null;

    // Only split on dash if left part is numeric or __other_option__
    int dashIdx = raw.lastIndexOf('-');
    if (dashIdx > 0) {
      String left = raw.substring(0, dashIdx).trim();
      String right = raw.substring(dashIdx + 1).trim();
      if (!right.isEmpty() && (left.matches("\\d+") || "__other_option__".equalsIgnoreCase(left))) {
        main = left;
        otherText = right;
      }
    }

    List<QuestionOption> options = getQuestionOptionsSafely(question).stream()
        .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition())).toList();

    // Check if question has __other_option__
    // boolean hasOtherOption = options.stream().anyMatch(
    // opt -> opt.getValue() != null && "__other_option__".equalsIgnoreCase(opt.getValue()));

    String[] selectedOptions = main.split("\\|");
    List<Integer> selectedPositions = new ArrayList<>();

    for (String optionToken : selectedOptions) {
      String trimmed = optionToken.trim();
      if (trimmed.isEmpty())
        continue;

      if (!isPositionNumber(trimmed)) {
        errors.add(String.format(
            "Dòng %d, cột %s: Chỉ chấp nhận số thứ tự (1-%d) cho câu hỏi nhiều lựa chọn. Danh sách: %s",
            rowIndex, columnName, options.size(), formatOptionsWithPosition(options)));
        continue;
      }
      int position = Integer.parseInt(trimmed);
      if (position < 1 || position > options.size()) {
        errors.add(String.format(
            "Dòng %d, cột %s: Vị trí '%s' không hợp lệ. Các vị trí hợp lệ: 1-%d. Danh sách: %s",
            rowIndex, columnName, trimmed, options.size(), formatOptionsWithPosition(options)));
      } else {
        selectedPositions.add(position);
      }
    }

    // If other text provided, ensure 'Other' is among selections
    if (otherText != null && !otherText.isEmpty()) {
      int otherIndex = -1;
      for (int i = 0; i < options.size(); i++) {
        QuestionOption opt = options.get(i);
        if (opt.getValue() != null && "__other_option__".equalsIgnoreCase(opt.getValue())) {
          otherIndex = i + 1;
          break;
        }
      }
      if (otherIndex == -1) {
        errors.add(String.format(
            "Dòng %d, cột %s: Đã cung cấp ghi chú cho 'Khác' nhưng câu hỏi không có lựa chọn 'Khác'",
            rowIndex, columnName));
      } else if (!selectedPositions.contains(otherIndex)) {
        errors.add(String.format(
            "Dòng %d, cột %s: Đã cung cấp ghi chú '%s' nhưng lựa chọn không bao gồm 'Khác'. Vui lòng thêm vị trí %d để nhập ghi chú cho 'Khác'",
            rowIndex, columnName, otherText, otherIndex));
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

  /**
   * Safely get question options, handling lazy initialization
   */
  private List<QuestionOption> getQuestionOptionsSafely(Question question) {
    try {
      return question.getOptions() == null ? new ArrayList<>() : question.getOptions();
    } catch (org.hibernate.LazyInitializationException e) {
      log.debug("Lazy initialization exception for question options, reloading question: {}",
          question.getId());
      try {
        Question reloadedQuestion =
            questionRepository.findWithOptionsById(question.getId()).orElse(question);
        return reloadedQuestion.getOptions() == null ? new ArrayList<>()
            : reloadedQuestion.getOptions();
      } catch (Exception ex) {
        log.warn("Failed to reload question with options: {}", question.getId(), ex);
        return new ArrayList<>();
      }
    }
  }
}
