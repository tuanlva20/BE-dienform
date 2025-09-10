package com.dienform.tool.dienformtudong.fillrequest.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.entity.ReportPaymentOrder;
import com.dienform.common.entity.User;
// Transaction event imports removed - no longer needed
import com.dienform.common.exception.BadRequestException;
import com.dienform.common.exception.ResourceNotFoundException;
import com.dienform.common.repository.ReportPaymentOrderRepository;
import com.dienform.common.repository.UserRepository;
import com.dienform.common.util.DateTimeUtil;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.answerdistribution.repository.AnswerDistributionRepository;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.datamapping.service.GoogleSheetsService;
import com.dienform.tool.dienformtudong.fillrequest.dto.request.FillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.BatchProgressResponse;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequestMapping;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
// FillRequestCreatedEvent import removed - no longer needed
import com.dienform.tool.dienformtudong.fillrequest.mapper.FillRequestMapper;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestMappingRepository;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.fillrequest.service.BatchDataFillCampaignService;
import com.dienform.tool.dienformtudong.fillrequest.service.BatchProcessingService;
import com.dienform.tool.dienformtudong.fillrequest.service.DataFillCampaignService;
import com.dienform.tool.dienformtudong.fillrequest.service.FillRequestService;
import com.dienform.tool.dienformtudong.fillrequest.service.PriorityCalculationService;
import com.dienform.tool.dienformtudong.fillrequest.service.QueueManagementService;
import com.dienform.tool.dienformtudong.fillrequest.service.ScheduleDistributionService;
import com.dienform.tool.dienformtudong.fillrequest.validator.DataFillValidator;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.payment.service.PaymentRealtimeService;
import com.dienform.tool.dienformtudong.payment.service.UserBalanceService;
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
  private final BatchDataFillCampaignService batchDataFillCampaignService;
  private final ApplicationEventPublisher eventPublisher;
  private final com.dienform.realtime.FillRequestRealtimeGateway realtimeGateway;
  private final com.dienform.common.util.CurrentUserUtil currentUserUtil;
  private final QueueManagementService queueManagementService;
  private final PriorityCalculationService priorityCalculationService;
  private final UserBalanceService userBalanceService;
  private final PaymentRealtimeService paymentRealtimeService;
  private final ReportPaymentOrderRepository paymentOrderRepository;
  private final UserRepository userRepository;

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

    // If endDate is null, set it to end of startDate day (23:59)
    if (endDate == null && startDate != null) {
      endDate = startDate.toLocalDate().atTime(23, 59);
      log.info("endDate is null, setting to end of startDate day (23:59): {}", endDate);
    }

    // Validate date range to prevent thread hanging
    if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
      throw new BadRequestException("Ngày bắt đầu không thể sau ngày kết thúc. StartDate: "
          + startDate + ", EndDate: " + endDate);
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
            .humanLike(Boolean.TRUE.equals(fillRequestDTO.getIsHumanLike())).startDate(startDate)
            .endDate(endDate).status(FillRequestStatusEnum.QUEUED).priority(0).build();

    // Deduct user balance and create withdrawal payment order within same transaction
    BigDecimal totalPrice = fillRequest.getTotalPrice();
    String currentUserId = currentUserUtil.requireCurrentUserId().toString();
    try {
      // Get user for payment order creation
      User user = userRepository.findById(UUID.fromString(currentUserId))
          .orElseThrow(() -> new ResourceNotFoundException("User", "id", currentUserId));

      // Deduct balance first
      userBalanceService.deductBalance(currentUserId, totalPrice);

      // Create withdrawal payment order to track spending
      ReportPaymentOrder withdrawalOrder = ReportPaymentOrder.builder().user(user)
          .amount(totalPrice).paymentType(ReportPaymentOrder.PaymentType.WITHDRAWAL)
          .status(ReportPaymentOrder.PaymentStatus.COMPLETED)
          .description("Chi phí điền form - " + form.getName() + " ("
              + fillRequestDTO.getSurveyCount() + " surveys)")
          .transactionId("WITHDRAWAL_" + System.currentTimeMillis()).isPromotional(false)
          .isReported(false).build();

      paymentOrderRepository.save(withdrawalOrder);

      // Emit realtime balance update
      paymentRealtimeService.emitBalanceUpdate(currentUserId);

      log.info("Created withdrawal payment order: {} for fill request amount: {}",
          withdrawalOrder.getId(), totalPrice);

    } catch (Exception e) {
      log.error("Balance deduction failed for user {} amount {}: {}", currentUserId, totalPrice,
          e.getMessage());
      throw new BadRequestException("Số dư không đủ hoặc không thể trừ tiền");
    }

    FillRequest savedRequest = fillRequestRepository.save(fillRequest);
    log.info("Fill request saved with ID: {}", savedRequest.getId());

    // Calculate and update priority based on business logic
    int calculatedPriority = calculatePriorityForRequest(savedRequest, fillRequestDTO);
    savedRequest.setPriority(calculatedPriority);

    // Calculate and set estimated completion date
    LocalDateTime estimatedCompletionDate =
        scheduleDistributionService.calculateEstimatedCompletionTime(savedRequest.getSurveyCount(),
            savedRequest.getStartDate(), savedRequest.getEndDate(), savedRequest.isHumanLike(),
            savedRequest.getCompletedSurvey());
    savedRequest.setEstimatedCompletionDate(estimatedCompletionDate);

    fillRequestRepository.save(savedRequest);

    log.info("Fill request {} created with calculated priority: {} ({})", savedRequest.getId(),
        calculatedPriority,
        priorityCalculationService.getPriorityLevelDescription(calculatedPriority));

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
      List<FillRequestDTO.AnswerDistributionRequest> distributionRequests = entry.getValue();

      Question question = questions.stream().filter(q -> q.getId().equals(questionId)).findFirst()
          .orElseThrow(() -> new BadRequestException("Question not found: " + questionId));

      // Create answer distributions for this question
      for (FillRequestDTO.AnswerDistributionRequest distributionRequest : distributionRequests) {
        AnswerDistribution distribution = AnswerDistribution.builder().fillRequest(savedRequest)
            .question(question).percentage(distributionRequest.getPercentage().intValue()).build();

        // Handle option-based distributions
        if (distributionRequest.getOptionId() != null) {
          QuestionOption option = optionRepository.findById(distributionRequest.getOptionId())
              .orElseThrow(() -> new BadRequestException(
                  "Question option not found: " + distributionRequest.getOptionId()));
          distribution.setOption(option);
        }

        // Handle text-based distributions
        if (distributionRequest.getValueString() != null) {
          distribution.setValueString(distributionRequest.getValueString());
        }

        // Handle rowId for grid questions
        if (distributionRequest.getRowId() != null) {
          distribution.setRowId(distributionRequest.getRowId());
        }

        // Handle positionIndex for text questions
        if (distributionRequest.getPositionIndex() != null) {
          distribution.setPositionIndex(distributionRequest.getPositionIndex());
        }

        distributions.add(distribution);
      }
    }

    // Process questions that don't have user-provided distributions
    Set<UUID> questionsWithDistributions = groupedByQuestion.keySet();
    for (Question question : questions) {
      if (!questionsWithDistributions.contains(question.getId())) {
        // Create default distribution for this question
        AnswerDistribution defaultDistribution = AnswerDistribution.builder()
            .fillRequest(savedRequest).question(question).percentage(100).build();

        // For questions with options, select the first option as default
        if (!question.getOptions().isEmpty()) {
          defaultDistribution.setOption(question.getOptions().get(0));
        }

        distributions.add(defaultDistribution);
      }
    }

    distributionRepository.saveAll(distributions);
    log.info("Created {} answer distributions for fill request {}", distributions.size(),
        savedRequest.getId());

    // Always add to queue instead of starting immediately
    // This ensures proper queue management and prevents race conditions
    log.info("Adding fill request {} to queue for proper scheduling", savedRequest.getId());

    // The request is already in QUEUED status, so we just need to ensure it's properly queued
    // The scheduler will pick it up when there's capacity and it's time to start

    return fillRequestMapper.toReponse(savedRequest);
  }

  // Event listener removed - fill requests will be processed by the scheduler instead
  // This ensures proper queue management and prevents immediate execution

  @Override
  public FillRequestResponse getFillRequestById(UUID id) {
    FillRequest fillRequest = fillRequestRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Fill Request", "id", id));

    List<AnswerDistribution> distributions = distributionRepository.findByFillRequestId(id);

    return mapToFillRequestResponse(fillRequest, distributions);
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

    // If endDate is null, set it to end of startDate day (23:59)
    if (endDate == null && startDate != null) {
      endDate = startDate.toLocalDate().atTime(23, 59);
      log.info("Data fill - endDate is null, setting to end of startDate day (23:59): {}", endDate);
    }

    // Validate date range to prevent thread hanging
    if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
      throw new BadRequestException("Ngày bắt đầu không thể sau ngày kết thúc. StartDate: "
          + startDate + ", EndDate: " + endDate);
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
        .endDate(endDate).status(FillRequestStatusEnum.QUEUED).priority(0).build();

    // Deduct user balance within same transaction
    BigDecimal totalPrice = fillRequest.getTotalPrice();
    String currentUserId = currentUserUtil.requireCurrentUserId().toString();
    try {
      // Get user for payment order creation
      User user = userRepository.findById(UUID.fromString(currentUserId))
          .orElseThrow(() -> new ResourceNotFoundException("User", "id", currentUserId));

      // Deduct balance first
      userBalanceService.deductBalance(currentUserId, totalPrice);

      // Create withdrawal payment order to track spending
      ReportPaymentOrder withdrawalOrder = ReportPaymentOrder.builder().user(user)
          .amount(totalPrice).paymentType(ReportPaymentOrder.PaymentType.WITHDRAWAL)
          .status(ReportPaymentOrder.PaymentStatus.COMPLETED)
          .description("Chi phí điền form dữ liệu - " + form.getName() + " ("
              + requestedSubmissionCount + " submissions)")
          .transactionId("WITHDRAWAL_DATA_" + System.currentTimeMillis()).isPromotional(false)
          .isReported(false).build();

      paymentOrderRepository.save(withdrawalOrder);

      // Balance update event will be automatically published by UserBalanceService
      // No need to manually call paymentRealtimeService.emitBalanceUpdate(currentUserId);

      log.info("Created withdrawal payment order: {} for data fill request amount: {}",
          withdrawalOrder.getId(), totalPrice);

    } catch (Exception e) {
      log.error("Data fill - Balance deduction failed for user {} amount {}: {}", currentUserId,
          totalPrice, e.getMessage());
      throw new BadRequestException("Số dư không đủ hoặc không thể trừ tiền");
    }

    FillRequest savedRequest = fillRequestRepository.save(fillRequest);

    // Calculate and update priority based on business logic
    int calculatedPriority = calculatePriorityForDataFillRequest(savedRequest, dataFillRequestDTO);
    savedRequest.setPriority(calculatedPriority);

    // Calculate and set estimated completion date
    LocalDateTime estimatedCompletionDate =
        scheduleDistributionService.calculateEstimatedCompletionTime(savedRequest.getSurveyCount(),
            savedRequest.getStartDate(), savedRequest.getEndDate(), savedRequest.isHumanLike(),
            savedRequest.getCompletedSurvey());
    savedRequest.setEstimatedCompletionDate(estimatedCompletionDate);

    fillRequestRepository.save(savedRequest);

    log.info("Data fill request {} created with calculated priority: {} ({})", savedRequest.getId(),
        calculatedPriority,
        priorityCalculationService.getPriorityLevelDescription(calculatedPriority));

    // Step 6: Create AnswerDistribution for "other" options with valueString
    // Commented out to avoid storing in database - using direct processing instead
    // The "other" option text will be processed directly in DataFillCampaignService
    // and passed through localOtherText map during form filling
    /*
     * List<AnswerDistribution> otherDistributions = createOtherAnswerDistributions(savedRequest,
     * questions, dataFillRequestDTO, sheetData); if (!otherDistributions.isEmpty()) {
     * distributionRepository.saveAll(otherDistributions);
     * log.info("Created {} AnswerDistribution records for 'other' options with valueString",
     * otherDistributions.size()); }
     */
    log.info(
        "Skipping AnswerDistribution creation for 'other' options - using direct processing from DataFillCampaignService");

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

    log.info("Created data fill request with ID: {} with {} mappings", savedRequest.getId(),
        mappings.size());

    // Step 8: Add to queue for proper scheduling
    // This ensures consistent queue management and prevents race conditions
    log.info("Adding data fill request {} to queue for proper scheduling", savedRequest.getId());

    // The request is already in QUEUED status, so we just need to ensure it's properly queued
    // The scheduler will pick it up when there's capacity and it's time to start
    // Batch processing will be handled by BatchDataFillCampaignService when the request is
    // processed

    return fillRequestMapper.toReponse(savedRequest);
  }

  @Override
  public BatchProgressResponse getBatchProgress(UUID requestId) {
    try {
      // Check if batch processing is enabled
      boolean batchProcessingEnabled = batchDataFillCampaignService.isBatchProcessingEnabled();

      if (!batchProcessingEnabled) {
        return BatchProgressResponse.builder().batchProcessingEnabled(false)
            .status("BATCH_PROCESSING_DISABLED").message("Batch processing is not enabled").build();
      }

      // Get batch progress from BatchDataFillCampaignService
      BatchProcessingService.BatchProgress progress =
          batchDataFillCampaignService.getBatchProgress(requestId);

      if (progress == null) {
        return BatchProgressResponse.builder().batchProcessingEnabled(true)
            .status("NO_BATCH_SCHEDULE").message("No batch schedule found for this request")
            .build();
      }

      return BatchProgressResponse.builder().currentBatch(progress.getCurrentBatch())
          .totalBatches(progress.getTotalBatches()).batchSize(progress.getBatchSize())
          .estimatedCompletionDate(progress.getEstimatedCompletionDate())
          .adjustedForTimeConstraint(progress.isAdjustedForTimeConstraint())
          .batchProcessingEnabled(true).status("BATCH_IN_PROGRESS")
          .message("Batch processing in progress").build();

    } catch (Exception e) {
      log.error("Error getting batch progress for request {}: {}", requestId, e.getMessage(), e);
      return BatchProgressResponse.builder().batchProcessingEnabled(true).status("ERROR")
          .message("Error retrieving batch progress: " + e.getMessage()).build();
    }
  }

  /**
   * Calculate priority for a fill request based on business logic
   */
  private int calculatePriorityForRequest(FillRequest request, FillRequestDTO requestDTO) {
    // Calculate age-based priority
    int agePriority = priorityCalculationService.calculateAgeBasedPriority(request.getCreatedAt());

    // Determine if this is a high-value request
    boolean isHighValue = request.getTotalPrice().compareTo(BigDecimal.valueOf(1000000)) > 0;

    // Determine if this is urgent (startDate is close to now)
    boolean isUrgent = false;
    if (request.getStartDate() != null) {
      long hoursUntilStart =
          java.time.Duration.between(DateTimeUtil.now(), request.getStartDate()).toHours();
      isUrgent = hoursUntilStart < 1; // Less than 1 hour until start
    }

    // Calculate priority with human-like factor consideration
    int calculatedPriority = priorityCalculationService.calculatePriorityWithHumanFactor(
        request.getCreatedAt(), request.isHumanLike(), isHighValue, isUrgent);

    log.debug(
        "Priority calculation for request {}: agePriority={}, isHighValue={}, isUrgent={}, isHumanLike={}, finalPriority={}",
        request.getId(), agePriority, isHighValue, isUrgent, request.isHumanLike(),
        calculatedPriority);

    return calculatedPriority;
  }

  /**
   * Calculate priority for data fill request
   */
  private int calculatePriorityForDataFillRequest(FillRequest request,
      DataFillRequestDTO requestDTO) {
    // Calculate age-based priority
    int agePriority = priorityCalculationService.calculateAgeBasedPriority(request.getCreatedAt());

    // Determine if this is a high-value request
    boolean isHighValue = request.getTotalPrice().compareTo(BigDecimal.valueOf(1000000)) > 0;

    // Determine if this is urgent (startDate is close to now)
    boolean isUrgent = false;
    if (request.getStartDate() != null) {
      long hoursUntilStart =
          java.time.Duration.between(DateTimeUtil.now(), request.getStartDate()).toHours();
      isUrgent = hoursUntilStart < 1; // Less than 1 hour until start
    }

    // Calculate priority with human-like factor consideration
    int calculatedPriority = priorityCalculationService.calculatePriorityWithHumanFactor(
        request.getCreatedAt(), request.isHumanLike(), isHighValue, isUrgent);

    log.debug(
        "Priority calculation for data fill request {}: agePriority={}, isHighValue={}, isUrgent={}, isHumanLike={}, finalPriority={}",
        request.getId(), agePriority, isHighValue, isUrgent, request.isHumanLike(),
        calculatedPriority);

    return calculatedPriority;
  }

  /**
   * Create AnswerDistribution records for "other" options with valueString from sheet data
   */
  private List<AnswerDistribution> createOtherAnswerDistributions(FillRequest fillRequest,
      List<Question> questions, DataFillRequestDTO dataFillRequestDTO,
      List<Map<String, Object>> sheetData) {

    List<AnswerDistribution> distributions = new ArrayList<>();
    Map<UUID, Question> questionMap =
        questions.stream().collect(Collectors.toMap(Question::getId, q -> q));

    // Process each mapping to find "other" options
    for (var mapping : dataFillRequestDTO.getMappings()) {
      String rawQuestionId = mapping.getQuestionId();
      String baseQuestionId = rawQuestionId;
      if (rawQuestionId != null && rawQuestionId.contains(":")) {
        baseQuestionId = rawQuestionId.split(":", 2)[0];
      }

      Question question = questionMap.get(UUID.fromString(baseQuestionId));
      if (question == null) {
        continue;
      }

      // Check if this question has "__other_option__"
      boolean hasOtherOption =
          question.getOptions() != null && question.getOptions().stream().anyMatch(
              opt -> opt.getValue() != null && "__other_option__".equalsIgnoreCase(opt.getValue()));

      if (!hasOtherOption) {
        continue;
      }

      // Find the "__other_option__" option
      QuestionOption otherOption = question.getOptions().stream()
          .filter(
              opt -> opt.getValue() != null && "__other_option__".equalsIgnoreCase(opt.getValue()))
          .findFirst().orElse(null);

      if (otherOption == null) {
        continue;
      }

      // Extract column name
      String columnName = extractColumnName(mapping.getColumnName());

      // Collect all unique "other" text values from sheet data
      Set<String> uniqueOtherTexts = new HashSet<>();

      for (Map<String, Object> rowData : sheetData) {
        Object value = rowData.get(columnName);
        if (value == null) {
          continue;
        }

        String valueStr = value.toString().trim();
        int dashIdx = valueStr.lastIndexOf('-');

        // Check if this is in "otherIndex-text" format
        if (dashIdx > 0) {
          String main = valueStr.substring(0, dashIdx).trim();
          String otherText = valueStr.substring(dashIdx + 1).trim();

          // Check if main part is a number (position) and otherText is not empty
          if (main.matches("\\d+") && !otherText.isEmpty()) {
            // Check if this position corresponds to the "other" option
            try {
              int position = Integer.parseInt(main);
              if (otherOption.getPosition() != null && otherOption.getPosition() == position) {
                uniqueOtherTexts.add(otherText);
                log.debug("Added 'other' text '{}' for matching position {}", otherText, position);
              } else {
                // If position doesn't match, but this is the only option with text after dash,
                // and the text doesn't match any predefined option, treat it as "other" text
                boolean isOptionValue = question.getOptions().stream()
                    .anyMatch(opt -> opt.getValue() != null && otherText.equals(opt.getValue()));

                if (!isOptionValue) {
                  uniqueOtherTexts.add(otherText);
                  log.debug(
                      "Added 'other' text '{}' for non-matching position {} (text not in predefined options)",
                      otherText, position);
                }
              }
            } catch (NumberFormatException e) {
              // Ignore if position is not a valid number
            }
          }
        } else {
          // Handle case where there's no dash but the value might be for "other" option
          // This could happen if the value is just the text without position prefix
          // We'll add it as a potential "other" text if it's not a numeric position
          if (!valueStr.matches("\\d+") && !valueStr.isEmpty()) {
            // Check if this question has any option that matches this value
            boolean isOptionValue = question.getOptions().stream()
                .anyMatch(opt -> opt.getValue() != null && valueStr.equals(opt.getValue()));

            // If it's not a predefined option value, treat it as "other" text
            if (!isOptionValue) {
              uniqueOtherTexts.add(valueStr);
              log.debug("Added 'other' text '{}' (no dash format)", valueStr);
            }
          }
        }
      }

      // Create AnswerDistribution for each unique "other" text
      for (String otherText : uniqueOtherTexts) {
        AnswerDistribution distribution = AnswerDistribution.builder().fillRequest(fillRequest)
            .question(question).option(otherOption).percentage(100) // 100% since it's from sheet
                                                                    // data
            .count(1) // At least 1
            .valueString(otherText).positionIndex(0).build();

        distributions.add(distribution);
        log.debug("Created AnswerDistribution for question {} with 'other' text: {}",
            question.getId(), otherText);
      }
    }

    return distributions;
  }

  /**
   * Extract column name from formatted string "A - Column Name"
   */
  private String extractColumnName(String formattedColumnName) {
    if (formattedColumnName.contains(" - ")) {
      return formattedColumnName.substring(formattedColumnName.indexOf(" - ") + 3);
    }
    return formattedColumnName;
  }

  /**
   * Parse UTC ISO 8601 string to LocalDateTime in Vietnam timezone
   */
  private LocalDateTime parseUtcStringToVietnamTime(String utcString) {
    if (utcString == null || utcString.trim().isEmpty()) {
      return null;
    }
    try {
      // Use the new DateTimeUtil method for parsing ISO 8601
      return DateTimeUtil.parseIso8601ToVietnamTime(utcString);
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
      // Check if datetime is reasonable (not too far in past/future)
      LocalDateTime now = DateTimeUtil.now();
      if (dateTime.isAfter(now.plusYears(10))) {
        log.warn(
            "Timezone validation warning for {}: DateTime {} is too far in future, possible timezone issue",
            fieldName, dateTime);
      }

      if (dateTime.isBefore(now.minusYears(10))) {
        log.warn(
            "Timezone validation warning for {}: DateTime {} is too far in past, possible timezone issue",
            fieldName, dateTime);
      }

      log.debug("Timezone validation passed for {}: {}", fieldName,
          DateTimeUtil.formatForLog(dateTime));

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
        .humanLike(fillRequest.isHumanLike()).createdAt(fillRequest.getCreatedAt())
        .startDate(fillRequest.getStartDate()).endDate(fillRequest.getEndDate())
        .status(fillRequest.getStatus() != null ? fillRequest.getStatus().name() : null);

    // Create answer distribution responses
    List<FillRequestResponse.AnswerDistributionResponse> distributionResponses =
        distributions.stream().map(distribution -> {
          FillRequestResponse.AnswerDistributionResponse.AnswerDistributionResponseBuilder builder =
              FillRequestResponse.AnswerDistributionResponse.builder()
                  .questionId(distribution.getQuestion().getId())
                  .percentage(distribution.getPercentage())
                  .count(distribution.getCount() != null ? distribution.getCount() : 0) // Handle
                                                                                        // null
                                                                                        // count
                  .valueString(distribution.getValueString()).rowId(distribution.getRowId())
                  .positionIndex(distribution.getPositionIndex());

          // Add optionId and option info if available
          if (distribution.getOption() != null) {
            builder.optionId(distribution.getOption().getId())
                .option(FillRequestResponse.AnswerDistributionResponse.OptionInfo.builder()
                    .id(distribution.getOption().getId()).text(distribution.getOption().getText())
                    .build());
          } else {
            // Set optionId to null explicitly for text questions
            builder.optionId(null);
          }

          return builder.build();
        }).collect(Collectors.toList());

    return responseBuilder.answerDistributions(distributionResponses).build();
  }
}
