package com.dienform.tool.dienformtudong.fillrequest.service.impl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import com.dienform.common.exception.BadRequestException;
import com.dienform.common.exception.ResourceNotFoundException;
import com.dienform.common.util.DateTimeUtil;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.answerdistribution.repository.AnswerDistributionRepository;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.datamapping.service.GoogleSheetsService;
import com.dienform.tool.dienformtudong.fillrequest.dto.request.FillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequestMapping;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.fillrequest.event.FillRequestCreatedEvent;
import com.dienform.tool.dienformtudong.fillrequest.mapper.FillRequestMapper;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestMappingRepository;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.fillrequest.service.DataFillCampaignService;
import com.dienform.tool.dienformtudong.fillrequest.service.FillRequestService;
import com.dienform.tool.dienformtudong.fillrequest.service.ScheduleDistributionService;
import com.dienform.tool.dienformtudong.fillrequest.validator.DataFillValidator;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import com.dienform.tool.dienformtudong.question.repository.QuestionOptionRepository;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FillRequestServiceImpl implements FillRequestService {

  private final FillRequestRepository fillRequestRepository;
  private final FillRequestMappingRepository fillRequestMappingRepository;
  private final AnswerDistributionRepository distributionRepository;
  private final QuestionOptionRepository optionRepository;
  private final FormRepository formRepository;
  private final QuestionRepository questionRepository;
  private final FillRequestMapper fillRequestMapper;
  private final GoogleFormService googleFormService;
  private final DataFillValidator dataFillValidator;
  private final ScheduleDistributionService scheduleDistributionService;
  private final GoogleSheetsService googleSheetsService;
  private final DataFillCampaignService dataFillCampaignService;
  private final ApplicationEventPublisher eventPublisher;
  private final com.dienform.realtime.FillRequestRealtimeGateway realtimeGateway;
  private final com.dienform.common.util.CurrentUserUtil currentUserUtil;

  @PersistenceContext
  private EntityManager entityManager;

  @Override
  @Transactional
  public FillRequestResponse createFillRequest(UUID formId, FillRequestDTO fillRequestDTO) {
    log.info("Creating fill request for form ID: {} with {} surveys", formId,
        fillRequestDTO.getSurveyCount());

    // Validate form exists
    Form form = formRepository.findById(formId)
        .orElseThrow(() -> new ResourceNotFoundException("Form", "id", formId));
    List<Question> questions = questionRepository.findByForm(form);

    // Validate answer distributions
    validateAnswerDistributions(fillRequestDTO.getAnswerDistributions(), questions);

    // Parse ISO 8601 string to LocalDateTime in Vietnam timezone
    LocalDateTime startDate = fillRequestDTO.getStartDate() != null
        ? parseUtcStringToVietnamTime(fillRequestDTO.getStartDate())
        : null;
    LocalDateTime endDate = fillRequestDTO.getEndDate() != null
        ? parseUtcStringToVietnamTime(fillRequestDTO.getEndDate())
        : null;

    // If endDate is null, set it to startDate and execute immediately
    if (endDate == null) {
      endDate = startDate;
      log.info("endDate is null, setting to startDate: {}", endDate);
    }

    // Validate timezone consistency
    validateTimezoneConsistency(startDate, "startDate");
    validateTimezoneConsistency(endDate, "endDate");

    log.info("Processing dates - startDate: {}, endDate: {}", startDate, endDate);
    log.debug("Timezone validation completed for startDate and endDate");

    // Create fill request
    FillRequest fillRequest =
        FillRequest.builder().form(form).surveyCount(fillRequestDTO.getSurveyCount())
            .completedSurvey(0).pricePerSurvey(fillRequestDTO.getPricePerSurvey())
            .totalPrice(fillRequestDTO.getPricePerSurvey()
                .multiply(BigDecimal.valueOf(fillRequestDTO.getSurveyCount())))
            .humanLike(fillRequestDTO.getIsHumanLike()).startDate(startDate).endDate(endDate)
            .status(FillRequestStatusEnum.PENDING).build();

    FillRequest savedRequest = fillRequestRepository.save(fillRequest);
    log.info("Fill request saved with ID: {}", savedRequest.getId());

    // Realtime: ensure current user joins user-specific room and receive bulk + initial update
    try {
      currentUserUtil.getCurrentUserIdIfPresent().ifPresent(uid -> {
        String userId = uid.toString();
        String formIdStr = form.getId().toString();
        realtimeGateway.emitBulkStateForUser(userId, formIdStr);
        com.dienform.realtime.dto.FillRequestUpdateEvent evt =
            com.dienform.realtime.dto.FillRequestUpdateEvent.builder().formId(formIdStr)
                .requestId(savedRequest.getId().toString()).status(savedRequest.getStatus().name())
                .completedSurvey(savedRequest.getCompletedSurvey())
                .surveyCount(savedRequest.getSurveyCount())
                .updatedAt(java.time.Instant.now().toString()).build();
        realtimeGateway.emitUpdateForUser(userId, formIdStr, evt);
      });
    } catch (Exception ignore) {
    }

    // Create answer distributions
    List<AnswerDistribution> distributions = new ArrayList<>();
    Map<UUID, List<FillRequestDTO.AnswerDistributionRequest>> groupedByQuestion =
        fillRequestDTO.getAnswerDistributions().stream().collect(
            Collectors.groupingBy(FillRequestDTO.AnswerDistributionRequest::getQuestionId));

    // Process questions that have user-provided distributions
    for (Map.Entry<UUID, List<FillRequestDTO.AnswerDistributionRequest>> entry : groupedByQuestion
        .entrySet()) {
      UUID questionId = entry.getKey();
      Question question = questions.stream().filter(q -> q.getId().equals(questionId)).findFirst()
          .orElseThrow(() -> new ResourceNotFoundException("Question", "id", questionId));
      List<FillRequestDTO.AnswerDistributionRequest> questionDistributions = entry.getValue();

      // Check if this is a matrix question (has rowId)
      boolean hasRowId = questionDistributions.stream()
          .anyMatch(dist -> dist.getRowId() != null && !dist.getRowId().isEmpty());

      if (hasRowId) {
        // For matrix questions, group by rowId and process each row separately
        Map<String, List<FillRequestDTO.AnswerDistributionRequest>> distributionsByRow =
            questionDistributions.stream()
                .filter(dist -> dist.getRowId() != null && !dist.getRowId().isEmpty())
                .collect(Collectors.groupingBy(FillRequestDTO.AnswerDistributionRequest::getRowId));

        for (Map.Entry<String, List<FillRequestDTO.AnswerDistributionRequest>> rowEntry : distributionsByRow
            .entrySet()) {
          String rowId = rowEntry.getKey();
          List<FillRequestDTO.AnswerDistributionRequest> rowDistributions = rowEntry.getValue();

          // Calculate count for each option in this row based on percentage
          for (FillRequestDTO.AnswerDistributionRequest dist : rowDistributions) {
            int count;
            QuestionOption option = null;

            if (dist.getPercentage() == 0.0 && dist.getOptionId() == null) {
              count = 1;
            } else {
              count = (int) Math
                  .round(fillRequestDTO.getSurveyCount() * (dist.getPercentage() / 100.0));
              option =
                  question.getOptions().stream().filter(o -> o.getId().equals(dist.getOptionId()))
                      .findAny().orElseThrow(() -> new ResourceNotFoundException("Question Option",
                          "id", dist.getOptionId()));
            }

            AnswerDistribution distribution = AnswerDistribution.builder().fillRequest(savedRequest)
                .question(question).option(option).percentage(dist.getPercentage().intValue())
                .count(count).rowId(rowId)
                .positionIndex(dist.getPositionIndex() != null ? dist.getPositionIndex() : 0)
                .build();

            distributions.add(distribution);
          }
        }
      } else {
        // For regular questions, process as before
        // Calculate count for each option based on percentage
        for (FillRequestDTO.AnswerDistributionRequest dist : questionDistributions) {
          int count;
          QuestionOption option = null;

          // Xử lý đặc biệt cho câu hỏi text
          if ("text".equalsIgnoreCase(question.getType())
              || "email".equalsIgnoreCase(question.getType())
              || "textarea".equalsIgnoreCase(question.getType())) {
            count =
                (int) Math.round(fillRequestDTO.getSurveyCount() * (dist.getPercentage() / 100.0));

            // Sử dụng builder trực tiếp thay vì factory method
            AnswerDistribution distribution = AnswerDistribution.builder().fillRequest(savedRequest)
                .question(question).option(null).percentage(dist.getPercentage().intValue())
                .count(count).valueString(dist.getValueString())
                .positionIndex(dist.getPositionIndex() != null ? dist.getPositionIndex() : 0)
                .build();

            distributions.add(distribution);
          } else {
            // Xử lý câu hỏi không phải text
            if (dist.getPercentage() == 0.0 && dist.getOptionId() == null) {
              count = 1;
            } else {
              count = (int) Math
                  .round(fillRequestDTO.getSurveyCount() * (dist.getPercentage() / 100.0));
              option =
                  question.getOptions().stream().filter(o -> o.getId().equals(dist.getOptionId()))
                      .findAny().orElseThrow(() -> new ResourceNotFoundException("Question Option",
                          "id", dist.getOptionId()));
            }

            // Ensure 'Khác' with valueString gets stored and count at least 1 when percentage = 0
            if (option != null && "__other_option__".equalsIgnoreCase(option.getValue())
                && dist.getValueString() != null && !dist.getValueString().trim().isEmpty()
                && dist.getPercentage() == 0.0) {
              count = 1;
            }

            AnswerDistribution distribution = AnswerDistribution.builder().fillRequest(savedRequest)
                .question(question).option(option).percentage(dist.getPercentage().intValue())
                .count(count).valueString(dist.getValueString())
                .positionIndex(dist.getPositionIndex() != null ? dist.getPositionIndex() : 0)
                .build();

            distributions.add(distribution);
          }
        }
      }
    }

    // Auto-generate distributions for required text questions that don't have user data
    for (Question question : questions) {
      if (Boolean.TRUE.equals(question.getRequired())
          && ("text".equalsIgnoreCase(question.getType())
              || "email".equalsIgnoreCase(question.getType())
              || "textarea".equalsIgnoreCase(question.getType()))) {

        // Check if this question already has distributions
        boolean hasDistribution = groupedByQuestion.containsKey(question.getId());

        if (!hasDistribution) {
          // Auto-generate a distribution for text questions
          int count = fillRequestDTO.getSurveyCount();
          AnswerDistribution distribution = AnswerDistribution.builder().fillRequest(savedRequest)
              .question(question).option(null).percentage(100) // 100% since it's required
              .count(count).valueString(null) // Will be auto-generated during form filling
              .build();

          distributions.add(distribution);
          log.info("Auto-generated distribution for required text question: {}",
              question.getTitle());
        }
      }
    }

    distributionRepository.saveAll(distributions);

    entityManager.flush();

    // CRITICAL FIX: Sử dụng event để đảm bảo transaction commit trước khi chạy async task
    FillRequestResponse response = fillRequestMapper.toReponse(savedRequest);

    // Publish event để trigger form filling sau khi transaction commit
    eventPublisher.publishEvent(new FillRequestCreatedEvent(this, savedRequest.getId()));

    return response;
  }

  /**
   * Event listener để start form filling sau khi transaction commit Đảm bảo không có race condition
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleFillRequestCreated(FillRequestCreatedEvent event) {
    try {
      log.info("Fill request created event received for ID: {}", event.getFillRequestId());

      // Start the form filling process asynchronously
      CompletableFuture.runAsync(() -> {
        try {
          // Thêm delay nhỏ để đảm bảo database transaction đã hoàn toàn commit
          Thread.sleep(200);

          log.info("Starting automated form filling for request ID: {}", event.getFillRequestId());
          googleFormService.fillForm(event.getFillRequestId());
        } catch (Exception e) {
          log.error("Error starting form filling process: {}", e.getMessage(), e);
          // Update request status to failed if there's an error
          try {
            FillRequest request =
                fillRequestRepository.findById(event.getFillRequestId()).orElse(null);
            if (request != null) {
              request.setStatus(FillRequestStatusEnum.FAILED);
              fillRequestRepository.save(request);
              try {
                com.dienform.realtime.dto.FillRequestUpdateEvent evt =
                    com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                        .formId(request.getForm().getId().toString())
                        .requestId(request.getId().toString())
                        .status(FillRequestStatusEnum.FAILED.name())
                        .completedSurvey(request.getCompletedSurvey())
                        .surveyCount(request.getSurveyCount())
                        .updatedAt(java.time.Instant.now().toString()).build();
                realtimeGateway.emitUpdate(request.getForm().getId().toString(), evt);
                currentUserUtil.getCurrentUserIdIfPresent().ifPresent(uid -> {
                  String userId = uid.toString();
                  String formIdStr = request.getForm().getId().toString();
                  realtimeGateway.emitUpdateForUser(userId, formIdStr, evt);
                  realtimeGateway.leaveUserFormRoom(userId, formIdStr);
                });
              } catch (Exception ignore) {
              }
            }
          } catch (Exception ex) {
            log.error("Error updating fill request status: {}", ex.getMessage(), ex);
          }
        }
      });
    } catch (Exception e) {
      log.error("Error handling fill request created event: {}", e.getMessage(), e);
    }
  }

  @Override
  public FillRequestResponse getFillRequestById(UUID id) {
    FillRequest fillRequest = fillRequestRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Fill Request", "id", id));

    List<AnswerDistribution> distributions = distributionRepository.findByFillRequestId(id);

    return mapToFillRequestResponse(fillRequest, distributions);
  }

  @Override
  @Transactional
  public Map<String, Object> startFillRequest(UUID id) {
    FillRequest fillRequest = fillRequestRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Fill Request", "id", id));

    // Validate fill request status before starting
    if (!(FillRequestStatusEnum.PENDING.equals(fillRequest.getStatus())
        || FillRequestStatusEnum.IN_PROCESS.equals(fillRequest.getStatus()))) {
      log.warn("Fill request {} is not in valid state to start. Current status: {}", id,
          fillRequest.getStatus());

      Map<String, Object> response = new HashMap<>();
      response.put("id", fillRequest.getId());
      response.put("status", fillRequest.getStatus());
      response.put("message", "Fill request is not in valid state to start");
      response.put("error", true);
      return response;
    }

    // If already running, return current status
    if (FillRequestStatusEnum.IN_PROCESS.equals(fillRequest.getStatus())) {
      log.info("Fill request {} is already running", id);

      Map<String, Object> response = new HashMap<>();
      response.put("id", fillRequest.getId());
      response.put("status", fillRequest.getStatus());
      response.put("message", "Fill request is already running");
      return response;
    }

    // Update status to IN_PROCESS
    fillRequest.setStatus(FillRequestStatusEnum.IN_PROCESS);
    fillRequestRepository.save(fillRequest);

    // Emit realtime update for IN_PROCESS transition
    try {
      com.dienform.realtime.dto.FillRequestUpdateEvent evt =
          com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
              .formId(fillRequest.getForm().getId().toString())
              .requestId(fillRequest.getId().toString())
              .status(FillRequestStatusEnum.IN_PROCESS.name())
              .completedSurvey(fillRequest.getCompletedSurvey())
              .surveyCount(fillRequest.getSurveyCount())
              .updatedAt(java.time.Instant.now().toString()).build();

      // Get current user ID if available
      String userId = null;
      try {
        userId = currentUserUtil.getCurrentUserIdIfPresent().map(UUID::toString).orElse(null);
      } catch (Exception ignore) {
        log.debug("Failed to get current user ID: {}", ignore.getMessage());
      }

      // Use centralized emit method with deduplication
      realtimeGateway.emitUpdateWithUser(fillRequest.getForm().getId().toString(), evt, userId);
    } catch (Exception ignore) {
      log.debug("Failed to emit IN_PROCESS update: {}", ignore.getMessage());
    }

    // Start form filling process asynchronously
    CompletableFuture.runAsync(() -> {
      try {
        log.info("Starting form filling process for request ID: {}", id);
        int successCount = googleFormService.fillForm(id);
        log.info("Form filling process completed for request ID: {}. Success count: {}", id,
            successCount);
      } catch (Exception e) {
        log.error("Error in form filling process for request ID {}: {}", id, e.getMessage(), e);
        // Update status to FAILED if there's an error
        try {
          FillRequest failedRequest = fillRequestRepository.findById(id).orElse(null);
          if (failedRequest != null) {
            failedRequest.setStatus(FillRequestStatusEnum.FAILED);
            fillRequestRepository.save(failedRequest);
            log.info("Updated fill request {} status to FAILED due to error", id);

            // Emit FAILED status update
            try {
              com.dienform.realtime.dto.FillRequestUpdateEvent evt =
                  com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                      .formId(failedRequest.getForm().getId().toString())
                      .requestId(failedRequest.getId().toString())
                      .status(FillRequestStatusEnum.FAILED.name())
                      .completedSurvey(failedRequest.getCompletedSurvey())
                      .surveyCount(failedRequest.getSurveyCount())
                      .updatedAt(java.time.Instant.now().toString()).build();

              // Get current user ID if available
              String userId = null;
              try {
                userId =
                    currentUserUtil.getCurrentUserIdIfPresent().map(UUID::toString).orElse(null);
              } catch (Exception ignore) {
                log.debug("Failed to get current user ID: {}", ignore.getMessage());
              }

              // Use centralized emit method with deduplication
              realtimeGateway.emitUpdateWithUser(failedRequest.getForm().getId().toString(), evt,
                  userId);
            } catch (Exception emitException) {
              log.debug("Failed to emit FAILED update: {}", emitException.getMessage());
            }
          }
        } catch (Exception updateException) {
          log.error("Failed to update fill request status to FAILED: {}",
              updateException.getMessage());
        }
      }
    });

    // Return response with status
    Map<String, Object> response = new HashMap<>();
    response.put("id", fillRequest.getId());
    response.put("status", fillRequest.getStatus());
    response.put("message", "Fill request started successfully");

    return response;
  }

  @Override
  @Transactional
  public Map<String, Object> resetFillRequest(UUID id) {
    FillRequest fillRequest = fillRequestRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Fill Request", "id", id));

    // Only allow reset if status is RUNNING or FAILED
    if (!(FillRequestStatusEnum.IN_PROCESS.equals(fillRequest.getStatus())
        || FillRequestStatusEnum.FAILED.equals(fillRequest.getStatus()))) {
      log.warn("Fill request {} cannot be reset. Current status: {}", id, fillRequest.getStatus());

      Map<String, Object> response = new HashMap<>();
      response.put("id", fillRequest.getId());
      response.put("status", fillRequest.getStatus());
      response.put("message", "Fill request cannot be reset from current status");
      response.put("error", true);
      return response;
    }

    // Reset status to PENDING
    fillRequest.setStatus(FillRequestStatusEnum.PENDING);
    fillRequestRepository.save(fillRequest);

    try {
      com.dienform.realtime.dto.FillRequestUpdateEvent evt =
          com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
              .formId(fillRequest.getForm().getId().toString())
              .requestId(fillRequest.getId().toString())
              .status(FillRequestStatusEnum.PENDING.name())
              .completedSurvey(fillRequest.getCompletedSurvey())
              .surveyCount(fillRequest.getSurveyCount())
              .updatedAt(java.time.Instant.now().toString()).build();

      // Get current user ID if available
      String userId = null;
      try {
        userId = currentUserUtil.getCurrentUserIdIfPresent().map(UUID::toString).orElse(null);
      } catch (Exception ignore) {
        log.debug("Failed to get current user ID: {}", ignore.getMessage());
      }

      // Use centralized emit method with deduplication
      realtimeGateway.emitUpdateWithUser(fillRequest.getForm().getId().toString(), evt, userId);
    } catch (Exception ignore) {
      log.debug("Failed to emit PENDING update: {}", ignore.getMessage());
    }

    log.info("Reset fill request {} status to PENDING", id);

    Map<String, Object> response = new HashMap<>();
    response.put("id", fillRequest.getId());
    response.put("status", fillRequest.getStatus());
    response.put("message", "Fill request reset successfully");

    return response;
  }

  @Override
  @Transactional
  public void deleteFillRequest(UUID id) {
    if (!fillRequestRepository.existsById(id)) {
      throw new ResourceNotFoundException("Fill Request", "id", id);
    }

    // Due to cascade delete in the database, this will delete related distributions
    fillRequestRepository.deleteById(id);
  }

  @Override
  @Transactional
  public FillRequestResponse createDataFillRequest(DataFillRequestDTO dataFillRequestDTO) {
    log.info("Creating data fill request for form: {}", dataFillRequestDTO.getFormId());

    // Step 1: Validate basic request structure
    DataFillValidator.ValidationResult basicValidation =
        dataFillValidator.validateDataFillRequest(dataFillRequestDTO);
    if (!basicValidation.isValid()) {
      throw new BadRequestException("Validation failed: " + basicValidation.getFirstError());
    }

    // Step 2: Get form and questions
    UUID formId = UUID.fromString(dataFillRequestDTO.getFormId());
    Form form = formRepository.findById(formId)
        .orElseThrow(() -> new ResourceNotFoundException("Form", "id", formId));

    List<Question> questions = questionRepository.findByForm(form);
    if (questions.isEmpty()) {
      throw new BadRequestException("Form không có câu hỏi nào");
    }

    // Step 3: Read and validate sheet data
    List<Map<String, Object>> sheetData;
    try {
      sheetData = googleSheetsService.getSheetData(dataFillRequestDTO.getSheetLink());
    } catch (Exception e) {
      throw new BadRequestException("Không thể đọc dữ liệu từ Google Sheets: " + e.getMessage());
    }

    // Step 4: Validate sheet data against question types
    DataFillValidator.ValidationResult dataValidation =
        dataFillValidator.validateSheetData(sheetData, questions, dataFillRequestDTO);
    if (!dataValidation.isValid()) {
      throw new BadRequestException("Validation failed: " + dataValidation.getFirstError());
    }

    // Step 5: Create fill request
    // Get dates parsed from UTC by Jackson
    LocalDateTime startDate = dataFillRequestDTO.getStartDate();
    LocalDateTime endDate = dataFillRequestDTO.getEndDate();

    // If endDate is null, set it to startDate and execute immediately
    if (endDate == null) {
      endDate = startDate;
      log.info("Data fill - endDate is null, setting to startDate: {}", endDate);
    }

    // Validate timezone consistency
    validateTimezoneConsistency(startDate, "startDate");
    validateTimezoneConsistency(endDate, "endDate");

    log.info("Data fill - Processing dates - startDate: {}, endDate: {}", startDate, endDate);
    log.debug("Data fill - Timezone validation completed for startDate and endDate");

    // Persist the exact requested submission count. Data re-use will be handled at execution time.
    int requestedSubmissionCount = dataFillRequestDTO.getSubmissionCount();
    FillRequest fillRequest = FillRequest.builder().form(form).surveyCount(requestedSubmissionCount)
        .completedSurvey(0).pricePerSurvey(dataFillRequestDTO.getPricePerSurvey())
        .totalPrice(dataFillRequestDTO.getPricePerSurvey()
            .multiply(BigDecimal.valueOf(requestedSubmissionCount)))
        .humanLike(Boolean.TRUE.equals(dataFillRequestDTO.getIsHumanLike())).startDate(startDate)
        .endDate(endDate).status(FillRequestStatusEnum.PENDING).build();

    FillRequest savedRequest = fillRequestRepository.save(fillRequest);

    // Step 6: Create schedule distribution
    // Ensure the number of scheduled tasks matches the persisted surveyCount
    int effectiveTaskCount = savedRequest.getSurveyCount();
    List<ScheduleDistributionService.ScheduledTask> schedule =
        scheduleDistributionService.distributeSchedule(effectiveTaskCount, startDate, endDate,
            Boolean.TRUE.equals(dataFillRequestDTO.getIsHumanLike()));

    // Step 7: Store data mapping for campaign execution
    List<FillRequestMapping> mappings = new ArrayList<>();
    for (var mapping : dataFillRequestDTO.getMappings()) {
      String rawQuestionId = mapping.getQuestionId();
      String baseQuestionId = rawQuestionId;
      if (rawQuestionId != null && rawQuestionId.contains(":")) {
        baseQuestionId = rawQuestionId.split(":", 2)[0];
      }

      FillRequestMapping fillRequestMapping = FillRequestMapping.builder()
          .fillRequestId(savedRequest.getId()).questionId(UUID.fromString(baseQuestionId))
          .columnName(mapping.getColumnName()).sheetLink(dataFillRequestDTO.getSheetLink()).build();
      mappings.add(fillRequestMapping);
    }
    fillRequestMappingRepository.saveAll(mappings);

    log.info("Created data fill request with ID: {} and {} scheduled tasks with {} mappings",
        savedRequest.getId(), schedule.size(), mappings.size());

    // Step 8: Optionally start the campaign immediately if startDate is now or in the past
    if (startDate != null && startDate.isBefore(DateTimeUtil.nowVietnam().plusMinutes(5))) {
      log.info("Starting data fill campaign immediately for request: {}", savedRequest.getId());

      // Execute campaign asynchronously
      dataFillCampaignService.executeCampaign(savedRequest, dataFillRequestDTO, questions, schedule)
          .thenRun(
              () -> log.info("Campaign execution completed for request: {}", savedRequest.getId()))
          .exceptionally(throwable -> {
            log.error("Campaign execution failed for request: {}", savedRequest.getId(), throwable);
            return null;
          });
    }

    return fillRequestMapper.toReponse(savedRequest);
  }

  @Override
  public Map<String, Object> clearCaches() {
    try {
      // Clear GoogleFormService caches
      googleFormService.clearCaches();

      log.info("All caches cleared successfully");

      Map<String, Object> response = new HashMap<>();
      response.put("message", "All caches cleared successfully");
      response.put("success", true);
      return response;
    } catch (Exception e) {
      log.error("Error clearing caches: {}", e.getMessage(), e);

      Map<String, Object> response = new HashMap<>();
      response.put("message", "Error clearing caches: " + e.getMessage());
      response.put("success", false);
      response.put("error", true);
      return response;
    }
  }

  /**
   * Parse UTC ISO 8601 string to LocalDateTime in Vietnam timezone
   */
  private LocalDateTime parseUtcStringToVietnamTime(String utcString) {
    if (utcString == null || utcString.trim().isEmpty()) {
      return null;
    }
    try {
      // Parse ISO 8601 string to Instant
      Instant instant = Instant.parse(utcString);
      // Convert to LocalDateTime in UTC
      LocalDateTime utcDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
      // Convert to Vietnam timezone
      return DateTimeUtil.fromUtcToVietnam(utcDateTime);
    } catch (Exception e) {
      log.error("Error parsing UTC string '{}': {}", utcString, e.getMessage());
      throw new BadRequestException("Invalid date format: " + utcString);
    }
  }

  /**
   * Validate timezone consistency for input date times
   */
  private void validateTimezoneConsistency(LocalDateTime dateTime, String fieldName) {
    if (dateTime == null) {
      return; // null is valid
    }

    try {
      boolean isValid = DateTimeUtil.validateVietnamTimezone(dateTime, fieldName);
      if (!isValid) {
        log.warn("Timezone validation failed for {}: {}. Data may have timezone inconsistency.",
            fieldName, dateTime);
        // Don't throw exception, just log warning to avoid breaking existing functionality
      }

      // Log timezone offset for debugging
      int offsetHours = DateTimeUtil.getVietnamUtcOffsetHours(dateTime);
      log.debug("Vietnam timezone offset for {}: {} hours", fieldName, offsetHours);

    } catch (Exception e) {
      log.error("Error during timezone validation for {}: {}", fieldName, e.getMessage());
    }
  }

  private void validateAnswerDistributions(
      List<FillRequestDTO.AnswerDistributionRequest> distributions, List<Question> questions) {
    if (distributions == null || distributions.isEmpty()) {
      throw new BadRequestException("Answer distributions cannot be empty");
    }

    // Group distributions by question
    Map<UUID, List<FillRequestDTO.AnswerDistributionRequest>> distributionsByQuestion =
        distributions.stream().collect(
            Collectors.groupingBy(FillRequestDTO.AnswerDistributionRequest::getQuestionId));

    // Check all required questions are included and validate their distributions
    for (Question question : questions) {
      List<FillRequestDTO.AnswerDistributionRequest> questionDistributions =
          distributionsByQuestion.get(question.getId());

      // For required questions
      if (Boolean.TRUE.equals(question.getRequired())) {
        // For text questions, they don't need user data - BE will auto-generate
        if ("text".equalsIgnoreCase(question.getType())
            || "email".equalsIgnoreCase(question.getType())
            || "textarea".equalsIgnoreCase(question.getType())) {
          // Skip validation for text questions - they will be auto-generated
          continue;
        }

        if (questionDistributions == null || questionDistributions.isEmpty()) {
          throw new BadRequestException(String.format(
              "Required question '%s' must have answer distributions", question.getTitle()));
        }

        // Check if this is a matrix question (has rowId)
        boolean hasRowId = questionDistributions.stream()
            .anyMatch(dist -> dist.getRowId() != null && !dist.getRowId().isEmpty());

        if (hasRowId) {
          // For matrix questions, validate each row separately
          Map<String, List<FillRequestDTO.AnswerDistributionRequest>> distributionsByRow =
              questionDistributions.stream()
                  .filter(dist -> dist.getRowId() != null && !dist.getRowId().isEmpty()).collect(
                      Collectors.groupingBy(FillRequestDTO.AnswerDistributionRequest::getRowId));

          for (Map.Entry<String, List<FillRequestDTO.AnswerDistributionRequest>> rowEntry : distributionsByRow
              .entrySet()) {
            String rowId = rowEntry.getKey();
            List<FillRequestDTO.AnswerDistributionRequest> rowDistributions = rowEntry.getValue();

            double totalPercentage = rowDistributions.stream()
                .mapToDouble(FillRequestDTO.AnswerDistributionRequest::getPercentage).sum();

            if (totalPercentage > 100) {
              throw new BadRequestException(String.format(
                  "Total percentage for question '%s' row '%s' must be <= 100 percent, but was %.2f",
                  question.getTitle(), rowId, totalPercentage));
            }
          }
        } else {
          // For regular questions, check total percentage
          double totalPercentage = questionDistributions.stream()
              .mapToDouble(FillRequestDTO.AnswerDistributionRequest::getPercentage).sum();

          // Allow small floating point tolerance
          if (Math.abs(totalPercentage - 100.0) > 0.01) {
            throw new BadRequestException(String.format(
                "Total percentage for required question '%s' must be 100 percent, but was %.2f",
                question.getTitle(), totalPercentage));
          }
        }
      }

      // Validate each distribution
      if (questionDistributions != null) {
        for (FillRequestDTO.AnswerDistributionRequest distribution : questionDistributions) {
          // Validate percentage range
          if (distribution.getPercentage() < 0 || distribution.getPercentage() > 100) {
            throw new BadRequestException(String.format(
                "Percentage for question '%s' must be between 0 and 100, but was %.2f",
                question.getTitle(), distribution.getPercentage()));
          }

          // For non-text questions, validate option ID
          if (!"text".equalsIgnoreCase(question.getType())
              && !"email".equalsIgnoreCase(question.getType())
              && !"textarea".equalsIgnoreCase(question.getType())) {
            if (distribution.getOptionId() == null) {
              if (distribution.getPercentage() > 0.0) {
                throw new BadRequestException(String.format(
                    "Option ID is required for non-text question '%s' with non-zero percentage",
                    question.getTitle()));
              }
            } else {
              // Validate option belongs to question
              QuestionOption option = optionRepository.findById(distribution.getOptionId())
                  .orElseThrow(() -> new ResourceNotFoundException("Question Option", "id",
                      distribution.getOptionId()));

              if (!option.getQuestion().getId().equals(question.getId())) {
                throw new BadRequestException(
                    String.format("Option '%s' does not belong to question '%s'", option.getText(),
                        question.getTitle()));
              }
            }
          }
        }
      }
    }
  }

  private FillRequestResponse mapToFillRequestResponse(FillRequest fillRequest,
      List<AnswerDistribution> distributions) {
    // Tạo response với thông tin cơ bản từ FillRequest
    FillRequestResponse.FillRequestResponseBuilder responseBuilder = FillRequestResponse.builder()
        .id(fillRequest.getId()).surveyCount(fillRequest.getSurveyCount())
        .completedSurvey(fillRequest.getCompletedSurvey())
        .pricePerSurvey(fillRequest.getPricePerSurvey()).totalPrice(fillRequest.getTotalPrice())
        .isHumanLike(fillRequest.isHumanLike()).createdAt(fillRequest.getCreatedAt())
        .startDate(fillRequest.getStartDate()).endDate(fillRequest.getEndDate())
        .status(fillRequest.getStatus() != null ? fillRequest.getStatus().name() : null);

    // Create answer distribution responses
    List<FillRequestResponse.AnswerDistributionResponse> distributionResponses =
        distributions.stream().map(distribution -> {
          FillRequestResponse.AnswerDistributionResponse.AnswerDistributionResponseBuilder builder =
              FillRequestResponse.AnswerDistributionResponse.builder()
                  .questionId(distribution.getQuestion().getId())
                  .percentage(distribution.getPercentage()).count(distribution.getCount())
                  .valueString(distribution.getValueString()).rowId(distribution.getRowId())
                  .positionIndex(distribution.getPositionIndex());

          // Add optionId and option info if available
          if (distribution.getOption() != null) {
            builder.optionId(distribution.getOption().getId())
                .option(FillRequestResponse.AnswerDistributionResponse.OptionInfo.builder()
                    .id(distribution.getOption().getId()).text(distribution.getOption().getText())
                    .build());
          }

          return builder.build();
        }).collect(Collectors.toList());

    return responseBuilder.answerDistributions(distributionResponses).build();
  }
}
