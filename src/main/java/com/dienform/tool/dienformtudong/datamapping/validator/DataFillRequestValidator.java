package com.dienform.tool.dienformtudong.datamapping.validator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.dienform.common.util.DateTimeUtil;
import com.dienform.tool.dienformtudong.datamapping.dto.request.ColumnMapping;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import lombok.extern.slf4j.Slf4j;

/**
 * Validator for business logic related to data fill requests Provides comprehensive validation for
 * form filling operations
 */
@Component
@Slf4j
public class DataFillRequestValidator {

  /**
   * Result of data fill request validation
   */
  public static class ValidationResult {
    public static ValidationResult valid() {
      return new ValidationResult(true, new ArrayList<>());
    }

    public static ValidationResult invalid(List<String> errors) {
      return new ValidationResult(false, errors);
    }

    public static ValidationResult invalid(String error) {
      List<String> errors = new ArrayList<>();
      errors.add(error);
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
      return errors.isEmpty() ? null : errors.get(0);
    }
  }

  /**
   * Validates data fill request for business logic requirements
   * 
   * @param request The data fill request to validate
   * @return ValidationResult containing validation status and errors
   */
  public ValidationResult validateDataFillRequest(DataFillRequestDTO request) {
    log.debug("Validating data fill request for form: {}", request.getFormName());

    List<String> errors = new ArrayList<>();

    // Validate mappings
    ValidationResult mappingValidation = validateMappings(request.getMappings());
    if (!mappingValidation.isValid()) {
      errors.addAll(mappingValidation.getErrors());
    }

    // Validate submission count business rules
    ValidationResult submissionValidation = validateSubmissionCount(request.getSubmissionCount());
    if (!submissionValidation.isValid()) {
      errors.addAll(submissionValidation.getErrors());
    }

    // Validate date range if provided
    ValidationResult dateValidation =
        validateDateRange(request.getStartDate(), request.getEndDate());
    if (!dateValidation.isValid()) {
      errors.addAll(dateValidation.getErrors());
    }

    // Validate pricing if provided
    if (request.getPricePerSurvey() != null) {
      ValidationResult priceValidation = validatePricing(request.getPricePerSurvey());
      if (!priceValidation.isValid()) {
        errors.addAll(priceValidation.getErrors());
      }
    }

    if (errors.isEmpty()) {
      log.debug("Data fill request validation passed for form: {}", request.getFormName());
      return ValidationResult.valid();
    } else {
      log.warn("Data fill request validation failed for form: {} with errors: {}",
          request.getFormName(), errors);
      return ValidationResult.invalid(errors);
    }
  }

  /**
   * Calculate estimated completion time Business logic for time estimation
   */
  public int calculateEstimatedCompletionTime(int submissionCount, boolean isHumanLike) {
    // Base time per submission in seconds
    int baseTimePerSubmission = isHumanLike ? 60 : 10;

    // Add buffer time for larger batches
    if (submissionCount > 100) {
      baseTimePerSubmission += 5; // Additional 5 seconds for large batches
    }

    // Calculate total time and convert to minutes
    int totalTimeSeconds = submissionCount * baseTimePerSubmission;
    int estimatedMinutes = (totalTimeSeconds / 60);

    // Minimum estimation of 1 minute
    return Math.max(1, estimatedMinutes);
  }

  /**
   * Validate form ID format and existence
   */
  public ValidationResult validateFormId(String formId) {
    if (formId == null || formId.trim().isEmpty()) {
      return ValidationResult.invalid("Form ID không được để trống");
    }

    // Basic format validation (adjust pattern as needed)
    if (!formId.matches("^[a-zA-Z0-9_-]+$")) {
      return ValidationResult.invalid("Form ID có định dạng không hợp lệ");
    }

    // Length validation
    if (formId.length() < 3 || formId.length() > 50) {
      return ValidationResult.invalid("Form ID phải từ 3-50 ký tự");
    }

    return ValidationResult.valid();
  }

  /**
   * Validates that mappings are sufficient for form filling
   */
  private ValidationResult validateMappings(List<ColumnMapping> mappings) {
    if (mappings == null || mappings.isEmpty()) {
      return ValidationResult.invalid("Cần ít nhất một mapping hợp lệ");
    }

    List<String> errors = new ArrayList<>();

    // Check for valid mappings (at least one mapped column)
    long validMappings = mappings.stream()
        .filter(
            mapping -> mapping.getColumnName() != null && !mapping.getColumnName().trim().isEmpty())
        .count();

    if (validMappings == 0) {
      errors.add("Cần ít nhất một mapping có column được chọn");
    }

    // Check for duplicate question mappings
    long uniqueQuestions = mappings.stream().map(ColumnMapping::getQuestionId).distinct().count();

    if (uniqueQuestions != mappings.size()) {
      errors.add("Không được có câu hỏi trùng lặp trong mapping");
    }

    return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
  }

  /**
   * Validates submission count business rules
   */
  private ValidationResult validateSubmissionCount(Integer submissionCount) {
    if (submissionCount == null) {
      return ValidationResult.invalid("Số lượng submission không được để trống");
    }

    List<String> errors = new ArrayList<>();

    // Business rule: minimum submissions
    if (submissionCount < 1) {
      errors.add("Số lượng submission phải ít nhất là 1");
    }

    // Business rule: maximum submissions for safety
    if (submissionCount > 1000) {
      errors.add("Số lượng submission không được vượt quá 1000 để đảm bảo hiệu suất");
    }

    // Business rule: warn for large batches
    if (submissionCount > 100) {
      log.warn("Large submission count requested: {} submissions", submissionCount);
    }

    return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
  }

  /**
   * Validates date range business rules
   */
  private ValidationResult validateDateRange(LocalDateTime startDate, LocalDateTime endDate) {
    List<String> errors = new ArrayList<>();

    if (startDate != null && endDate != null) {
      // End date must be after start date
      if (endDate.isBefore(startDate)) {
        errors.add("Ngày kết thúc phải sau ngày bắt đầu");
      }

      // Start date cannot be in the past (with timezone consideration)
      LocalDateTime nowVietnam = DateTimeUtil.nowVietnam();
      if (startDate.isBefore(nowVietnam.minusMinutes(5))) {
        errors.add("Ngày bắt đầu không thể là thời điểm trong quá khứ");
      }

      // Check reasonable time range (not too far in future)
      if (startDate.isAfter(nowVietnam.plusYears(1))) {
        errors.add("Ngày bắt đầu không được quá 1 năm trong tương lai");
      }
    }

    return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
  }

  /**
   * Validates pricing business rules
   */
  private ValidationResult validatePricing(java.math.BigDecimal pricePerSurvey) {
    List<String> errors = new ArrayList<>();

    // Price must be positive
    if (pricePerSurvey.compareTo(java.math.BigDecimal.ZERO) < 0) {
      errors.add("Giá per survey không được âm");
    }

    // Reasonable price range check
    if (pricePerSurvey.compareTo(new java.math.BigDecimal("1000000")) > 0) {
      errors.add("Giá per survey quá cao (tối đa 1,000,000)");
    }

    return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
  }
}
