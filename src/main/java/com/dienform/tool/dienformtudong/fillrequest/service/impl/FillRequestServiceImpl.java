package com.dienform.tool.dienformtudong.fillrequest.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.exception.BadRequestException;
import com.dienform.common.exception.NotFoundException;
import com.dienform.common.exception.ResourceNotFoundException;
import com.dienform.common.util.Constants;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.answerdistribution.repository.AnswerDistributionRepository;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.request.FillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequestMapping;
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
  private final com.dienform.tool.dienformtudong.datamapping.service.GoogleSheetsService googleSheetsService;
  private final DataFillCampaignService dataFillCampaignService;

  @PersistenceContext
  private EntityManager entityManager;

  @Override
  @Transactional
  public FillRequestResponse createFillRequest(UUID formId, FillRequestDTO fillRequestDTO) {
    // Validate form exists
    Form form = formRepository.findById(formId)
        .orElseThrow(() -> new NotFoundException("Cannot find form with id: " + formId));
    List<Question> questions = questionRepository.findByForm(form);

    // Validate answer distributions
    validateAnswerDistributions(fillRequestDTO.getAnswerDistributions());

    // Create fill request
    FillRequest fillRequest = FillRequest.builder().form(form)
        .surveyCount(fillRequestDTO.getSurveyCount())
        .pricePerSurvey(fillRequestDTO.getPricePerSurvey())
        .totalPrice(fillRequestDTO.getPricePerSurvey()
            .multiply(BigDecimal.valueOf(fillRequestDTO.getSurveyCount())))
        .humanLike(fillRequestDTO.getIsHumanLike()).startDate(fillRequestDTO.getStartDate())
        .endDate(fillRequestDTO.getEndDate()).status(Constants.FILL_REQUEST_STATUS_PENDING).build();

    FillRequest savedRequest = fillRequestRepository.save(fillRequest);

    // Create answer distributions
    List<AnswerDistribution> distributions = new ArrayList<>();
    Map<UUID, List<FillRequestDTO.AnswerDistributionRequest>> groupedByQuestion =
        fillRequestDTO.getAnswerDistributions().stream().collect(
            Collectors.groupingBy(FillRequestDTO.AnswerDistributionRequest::getQuestionId));

    for (Map.Entry<UUID, List<FillRequestDTO.AnswerDistributionRequest>> entry : groupedByQuestion
        .entrySet()) {
      UUID questionId = entry.getKey();
      Question question = questions.stream().filter(q -> q.getId().equals(questionId)).findFirst()
          .orElseThrow(() -> new ResourceNotFoundException("Question", "id", questionId));
      List<FillRequestDTO.AnswerDistributionRequest> questionDistributions = entry.getValue();

      // Validate total percentage for each question is 100%
      int totalPercentage = questionDistributions.stream()
          .mapToInt(FillRequestDTO.AnswerDistributionRequest::getPercentage).sum();

      if (totalPercentage > 100) {
        throw new BadRequestException(
            String.format("Total percentage for question %s <= 100%, but" + " was %d%%", questionId,
                totalPercentage));
      }

      // Calculate count for each option based on percentage
      for (FillRequestDTO.AnswerDistributionRequest dist : questionDistributions) {
        int count;
        QuestionOption option = null;

        // Xử lý đặc biệt cho câu hỏi text
        if ("text".equalsIgnoreCase(question.getType())) {
          count =
              (int) Math.round(fillRequestDTO.getSurveyCount() * (dist.getPercentage() / 100.0));

          // Sử dụng builder trực tiếp thay vì factory method
          AnswerDistribution distribution = AnswerDistribution.builder().fillRequest(savedRequest)
              .question(question).option(null).percentage(dist.getPercentage()).count(count)
              .valueString(dist.getValueString()).build();

          distributions.add(distribution);
        } else {
          // Xử lý câu hỏi không phải text
          if (dist.getPercentage() == 0 && dist.getOptionId() == null) {
            count = 1;
          } else {
            count =
                (int) Math.round(fillRequestDTO.getSurveyCount() * (dist.getPercentage() / 100.0));
            option =
                question.getOptions().stream().filter(o -> o.getId().equals(dist.getOptionId()))
                    .findAny().orElseThrow(() -> new NotFoundException(
                        "Cannot find option with id: " + dist.getOptionId()));
          }

          AnswerDistribution distribution =
              AnswerDistribution.builder().fillRequest(savedRequest).question(question)
                  .option(option).percentage(dist.getPercentage()).count(count).build();

          distributions.add(distribution);
        }
      }
    }

    distributionRepository.saveAll(distributions);

    entityManager.flush();

    // Start the form filling process asynchronously
    CompletableFuture.runAsync(() -> {
      try {
        log.info("Starting automated form filling for request ID: {}", savedRequest.getId());
        googleFormService.fillForm(savedRequest.getId());
      } catch (Exception e) {
        log.error("Error starting form filling process: {}", e.getMessage(), e);
        // Update request status to failed if there's an error
        try {
          FillRequest request = fillRequestRepository.findById(savedRequest.getId()).orElse(null);
          if (request != null) {
            request.setStatus(Constants.FILL_REQUEST_STATUS_FAILED);
            fillRequestRepository.save(request);
          }
        } catch (Exception ex) {
          log.error("Error updating fill request status: {}", ex.getMessage(), ex);
        }
      }
    });
    return fillRequestMapper.toReponse(savedRequest);
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

    // You can add validation here to check if the request is in a valid state to start

    // Update status
    fillRequest.setStatus(Constants.FILL_REQUEST_STATUS_RUNNING);
    fillRequestRepository.save(fillRequest);

    // Start form filling process asynchronously
    CompletableFuture.runAsync(() -> {
      try {
        googleFormService.fillForm(id);
      } catch (Exception e) {
        log.error("Error in form filling process for request ID {}: {}", id, e.getMessage(), e);
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
        .orElseThrow(() -> new NotFoundException("Cannot find form with id: " + formId));

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
    FillRequest fillRequest = FillRequest.builder().form(form)
        .surveyCount(Math.min(dataFillRequestDTO.getSubmissionCount(), sheetData.size()))
        .pricePerSurvey(dataFillRequestDTO.getPricePerSurvey())
        .totalPrice(dataFillRequestDTO.getPricePerSurvey()
        .multiply(BigDecimal.valueOf(dataFillRequestDTO.getSubmissionCount())))
        .humanLike(Boolean.TRUE.equals(dataFillRequestDTO.getIsHumanLike()))
        .startDate(dataFillRequestDTO.getStartDate()).endDate(dataFillRequestDTO.getEndDate())
        .status(Constants.FILL_REQUEST_STATUS_PENDING).build();

    FillRequest savedRequest = fillRequestRepository.save(fillRequest);

    // Step 6: Create schedule distribution
    List<ScheduleDistributionService.ScheduledTask> schedule =
        scheduleDistributionService.distributeSchedule(dataFillRequestDTO.getSubmissionCount(),
            dataFillRequestDTO.getStartDate(), dataFillRequestDTO.getEndDate(),
            Boolean.TRUE.equals(dataFillRequestDTO.getIsHumanLike()));

    // Step 7: Store data mapping for campaign execution
    List<FillRequestMapping> mappings = new ArrayList<>();
    for (var mapping : dataFillRequestDTO.getMappings()) {
      FillRequestMapping fillRequestMapping = FillRequestMapping.builder()
          .fillRequestId(savedRequest.getId()).questionId(UUID.fromString(mapping.getQuestionId()))
          .columnName(mapping.getColumnName()).sheetLink(dataFillRequestDTO.getSheetLink()).build();
      mappings.add(fillRequestMapping);
    }
    fillRequestMappingRepository.saveAll(mappings);

    log.info("Created data fill request with ID: {} and {} scheduled tasks with {} mappings",
        savedRequest.getId(), schedule.size(), mappings.size());

    // Step 8: Optionally start the campaign immediately if startDate is now or in the past
    if (dataFillRequestDTO.getStartDate() != null
        && dataFillRequestDTO.getStartDate().isBefore(LocalDateTime.now().plusMinutes(5))) {
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

  private void validateAnswerDistributions(
      List<FillRequestDTO.AnswerDistributionRequest> distributions) {
    if (distributions == null || distributions.isEmpty()) {
      throw new BadRequestException("Answer distributions cannot be empty");
    }

    // Check that all question and option IDs exist
    for (FillRequestDTO.AnswerDistributionRequest distribution : distributions) {
      // Lấy thông tin question
      Question question = questionRepository.findById(distribution.getQuestionId()).orElseThrow(
          () -> new ResourceNotFoundException("Question", "id", distribution.getQuestionId()));

      // Với câu hỏi text, optionId có thể null
      if ("text".equalsIgnoreCase(question.getType())) {
        continue;
      }

      // Với câu hỏi không phải text, kiểm tra optionId
      if (distribution.getOptionId() == null) {
        if (distribution.getPercentage() == 0) {
          continue;
        } else {
          throw new BadRequestException(
              "Option ID is required for non-text questions with non-zero percentage");
        }
      }

      QuestionOption option = optionRepository.findById(distribution.getOptionId()).orElseThrow(
          () -> new ResourceNotFoundException("Question Option", "id", distribution.getOptionId()));

      // Ensure the option belongs to the specified question
      if (option != null && !option.getQuestion().getId().equals(distribution.getQuestionId())) {
        throw new BadRequestException(String.format("Option %s does not belong to question %s",
            distribution.getOptionId(), distribution.getQuestionId()));
      }
    }
  }

  private FillRequestResponse mapToFillRequestResponse(FillRequest fillRequest,
      List<AnswerDistribution> distributions) {
    // Tạo response với thông tin cơ bản từ FillRequest
    FillRequestResponse.FillRequestResponseBuilder responseBuilder = FillRequestResponse.builder()
        .id(fillRequest.getId()).surveyCount(fillRequest.getSurveyCount())
        .pricePerSurvey(fillRequest.getPricePerSurvey()).totalPrice(fillRequest.getTotalPrice())
        .isHumanLike(fillRequest.isHumanLike()).createdAt(fillRequest.getCreatedAt())
        .startDate(fillRequest.getStartDate()).endDate(fillRequest.getEndDate())
        .status(fillRequest.getStatus());

    // Create answer distribution responses
    List<FillRequestResponse.AnswerDistributionResponse> distributionResponses =
        distributions.stream().map(distribution -> {
          FillRequestResponse.AnswerDistributionResponse.AnswerDistributionResponseBuilder builder =
              FillRequestResponse.AnswerDistributionResponse.builder()
                  .questionId(distribution.getQuestion().getId())
                  .percentage(distribution.getPercentage()).count(distribution.getCount())
                  .valueString(distribution.getValueString());

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
