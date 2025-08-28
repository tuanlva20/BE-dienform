package com.dienform.tool.dienformtudong.datamapping.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataMappingRequest;
import com.dienform.tool.dienformtudong.datamapping.dto.response.DataFillRequestResponse;
import com.dienform.tool.dienformtudong.datamapping.dto.response.DataMappingResponse;
import com.dienform.tool.dienformtudong.datamapping.dto.response.SheetAccessibilityInfo;
import com.dienform.tool.dienformtudong.datamapping.service.GoogleSheetsService;
import com.dienform.tool.dienformtudong.datamapping.util.DataMappingUtil;
import com.dienform.tool.dienformtudong.datamapping.validator.DataFillRequestValidator;
import com.dienform.tool.dienformtudong.question.dto.response.QuestionResponse;
import com.dienform.tool.dienformtudong.question.service.QuestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Controller for data mapping operations between Google Forms and Google Sheets Provides endpoints
 * for validation, accessibility checking, and data mapping
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class DataMappingController {

  // DTO class for auto mapping results
  public static class AutoMapping {
    public final String questionId;
    public final String questionTitle;
    public final String columnName;
    public final double confidence;

    public AutoMapping(String questionId, String questionTitle, String columnName,
        double confidence) {
      this.questionId = questionId;
      this.questionTitle = questionTitle;
      this.columnName = columnName;
      this.confidence = confidence;
    }
  }

  @Autowired
  private GoogleSheetsService googleSheetsService;



  @Autowired
  private DataFillRequestValidator dataFillRequestValidator;

  @Autowired
  private QuestionService questionService;

  /**
   * Check and map data between selected form and Google Sheets This endpoint validates the sheet
   * URL, checks accessibility, and performs data mapping
   */
  @PostMapping("/data-mapping")
  public ResponseEntity<DataMappingResponse> checkDataMapping(
      @Valid @RequestBody DataMappingRequest request, HttpServletRequest httpRequest) {

    log.info("Received data mapping request for formId: {} and sheetLink: {}", request.getFormId(),
        request.getSheetLink());

    try {
      // Step 1: Validate form ID
      UUID formUUID;
      try {
        formUUID = UUID.fromString(request.getFormId());
      } catch (IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            DataMappingResponse.builder().errors(Arrays.asList("Form ID không hợp lệ")).build());
      }

      // Step 2: Validate and check sheet accessibility
      SheetAccessibilityInfo accessibilityInfo =
          googleSheetsService.validateAndCheckAccessibility(request.getSheetLink());

      log.info("Sheet accessibility check result: isAccessible={}, isPublic={}, method={}",
          accessibilityInfo.isAccessible(), accessibilityInfo.isPublic(),
          accessibilityInfo.getAccessMethod());

      // Step 3: Handle inaccessible sheets -> return 400 with clear error for FE
      if (!accessibilityInfo.isAccessible()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            DataMappingResponse.builder().errors(Arrays.asList(accessibilityInfo.getMessage()))
                .sheetAccessibilityInfo(accessibilityInfo).build());
      }

      // Step 4: Read sheet data (now we know it's accessible)
      List<String> rawSheetColumns = googleSheetsService.getSheetColumns(request.getSheetLink());
      log.info("Successfully retrieved {} columns from sheet", rawSheetColumns.size());

      // Step 5: Format sheet columns with Excel-style prefixes
      List<String> formattedSheetColumns = DataMappingUtil.formatSheetColumns(rawSheetColumns);
      log.info("Formatted {} columns with Excel-style prefixes", formattedSheetColumns.size());

      // Step 6: Query form questions from database
      List<QuestionResponse> rawFormQuestions = questionService.getQuestionsByFormId(formUUID);
      log.info("Successfully retrieved {} questions from form", rawFormQuestions.size());

      if (rawFormQuestions.isEmpty()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(DataMappingResponse.builder().errors(Arrays.asList("Form không có câu hỏi nào"))
                .sheetAccessibilityInfo(accessibilityInfo).build());
      }

      // Step 7: Clean question titles (remove * for required fields)
      List<QuestionResponse> cleanedFormQuestions =
          DataMappingUtil.cleanQuestionTitles(rawFormQuestions);
      log.info("Cleaned titles for {} questions", cleanedFormQuestions.size());

      // Step 8: Perform automatic mapping using similarity service (use raw column names for
      // comparison)
      List<AutoMapping> autoMappings =
          performAutoMapping(cleanedFormQuestions, rawSheetColumns, formattedSheetColumns);

      // Step 9: Find unmapped questions
      List<String> unmappedQuestions = findUnmappedQuestions(cleanedFormQuestions, autoMappings);

      // Step 10: Build successful response
      return ResponseEntity.ok(DataMappingResponse.builder()
          .questions(cleanedFormQuestions.stream().collect(Collectors.toList()))
          .sheetColumns(formattedSheetColumns)
          .autoMappings(autoMappings.stream().collect(Collectors.toList()))
          .unmappedQuestions(unmappedQuestions).sheetAccessibilityInfo(accessibilityInfo).build());

    } catch (Exception e) {
      log.error("Error during data mapping", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(DataMappingResponse.builder()
              .errors(Arrays.asList("Có lỗi xảy ra khi xử lý dữ liệu: " + e.getMessage())).build());
    }
  }

  /**
   * Create automated form filling request
   */
  @PostMapping("/data-fill-request")
  public ResponseEntity<DataFillRequestResponse> createDataFillRequest(
      @Valid @RequestBody DataFillRequestDTO request, HttpServletRequest httpRequest) {

    log.info("Received data fill request for form: {} with {} submissions", request.getFormName(),
        request.getSubmissionCount());

    try {
      // Step 1: Validate sheet accessibility
      SheetAccessibilityInfo accessibilityInfo =
          googleSheetsService.validateAndCheckAccessibility(request.getSheetLink());

      if (!accessibilityInfo.isAccessible()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(DataFillRequestResponse.builder().status("FAILED")
                .message("Sheet không thể truy cập: " + accessibilityInfo.getMessage()).build());
      }

      // Step 2: Validate business logic using validator
      DataFillRequestValidator.ValidationResult validationResult =
          dataFillRequestValidator.validateDataFillRequest(request);

      if (!validationResult.isValid()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(DataFillRequestResponse.builder()
            .status("FAILED").message(validationResult.getFirstError()).build());
      }

      // Step 3: Create fill request (mock implementation)
      String requestId = "req_" + System.currentTimeMillis();
      int estimatedTime = dataFillRequestValidator
          .calculateEstimatedCompletionTime(request.getSubmissionCount(), request.getIsHumanLike());

      return ResponseEntity.ok(DataFillRequestResponse.builder().id(requestId).status("PENDING")
          .message("Yêu cầu điền form đã được tạo thành công").estimatedTime(estimatedTime)
          .build());

    } catch (Exception e) {
      log.error("Error creating fill request", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(DataFillRequestResponse
          .builder().status("FAILED").message("Có lỗi hệ thống xảy ra: " + e.getMessage()).build());
    }
  }

  /**
   * Perform automatic mapping between form questions and sheet columns using similarity analysis
   * 
   * @param formQuestions List of questions from the form (with cleaned titles)
   * @param rawSheetColumns List of raw column names from the sheet (for similarity comparison)
   * @param formattedSheetColumns List of formatted column names (for display)
   * @return List of AutoMapping objects with confidence scores
   */
  private List<AutoMapping> performAutoMapping(List<QuestionResponse> formQuestions,
      List<String> rawSheetColumns, List<String> formattedSheetColumns) {
    List<AutoMapping> mappings = new ArrayList<>();

    // Build a quick lookup of normalized header -> index
    // Normalization: trim + lowercase
    java.util.Map<String, Integer> headerIndexByName = new java.util.HashMap<>();
    for (int i = 0; i < rawSheetColumns.size(); i++) {
      String header = rawSheetColumns.get(i);
      if (header != null) {
        headerIndexByName.put(header.trim().toLowerCase(), i);
      }
    }

    for (int qIndex = 0; qIndex < formQuestions.size(); qIndex++) {
      QuestionResponse question = formQuestions.get(qIndex);

      String normalizedTitle =
          question.getTitle() == null ? null : question.getTitle().trim().toLowerCase();
      String normalizedDescription =
          question.getDescription() == null ? null : question.getDescription().trim().toLowerCase();

      Integer matchIndex = null;
      double confidence = 0.0;

      // 1) Prefer exact header-name match by title
      if (normalizedTitle != null && headerIndexByName.containsKey(normalizedTitle)) {
        matchIndex = headerIndexByName.get(normalizedTitle);
        confidence = 1.0; // exact name match
      } else if (normalizedDescription != null
          && headerIndexByName.containsKey(normalizedDescription)) {
        // 2) If title not found, try exact match by description
        matchIndex = headerIndexByName.get(normalizedDescription);
        confidence = 0.9;
      } else {
        // 3) Fallback to positional mapping: question i -> column i (if present)
        if (qIndex < formattedSheetColumns.size()) {
          matchIndex = qIndex;
          confidence = 0.5;
        }
      }

      if (matchIndex != null) {
        String displayColumn = formattedSheetColumns.get(matchIndex);
        mappings.add(new AutoMapping(question.getId().toString(), question.getTitle(),
            displayColumn, confidence));
      }
    }

    return mappings;
  }

  /**
   * Find questions that couldn't be automatically mapped
   * 
   * @param formQuestions All questions from the form
   * @param autoMappings Successfully mapped questions
   * @return List of unmapped question titles
   */
  private List<String> findUnmappedQuestions(List<QuestionResponse> formQuestions,
      List<AutoMapping> autoMappings) {
    List<String> mappedQuestionIds =
        autoMappings.stream().map(mapping -> mapping.questionId).collect(Collectors.toList());

    return formQuestions.stream()
        .filter(question -> !mappedQuestionIds.contains(question.getId().toString()))
        .map(QuestionResponse::getTitle).collect(Collectors.toList());
  }
}
