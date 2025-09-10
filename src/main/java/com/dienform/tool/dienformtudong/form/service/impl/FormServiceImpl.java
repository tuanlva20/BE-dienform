package com.dienform.tool.dienformtudong.form.service.impl;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.exception.ResourceNotFoundException;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestMappingRepository;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.form.dto.param.FormParam;
import com.dienform.tool.dienformtudong.form.dto.request.FormRequest;
import com.dienform.tool.dienformtudong.form.dto.response.FormDetailResponse;
import com.dienform.tool.dienformtudong.form.dto.response.FormResponse;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.enums.FormStatusEnum;
import com.dienform.tool.dienformtudong.form.mapper.FormMapper;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.form.service.FormService;
import com.dienform.tool.dienformtudong.form.utils.SortUtil;
import com.dienform.tool.dienformtudong.formstatistic.entity.FormStatistic;
import com.dienform.tool.dienformtudong.formstatistic.repository.FormStatisticRepository;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.googleform.util.GoogleFormParser.ExtractedQuestion;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import com.dienform.tool.dienformtudong.question.repository.QuestionOptionRepository;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FormServiceImpl implements FormService {

  private static final Logger log = LoggerFactory.getLogger(FormServiceImpl.class);
  private final FormMapper formMapper;
  private final FormRepository formRepository;
  private final FormStatisticRepository formStatisticRepository;
  private final GoogleFormService googleFormService;
  private final QuestionRepository questionRepository;
  private final QuestionOptionRepository optionRepository;
  private final FillRequestRepository fillRequestRepository;
  private final FillRequestMappingRepository fillRequestMappingRepository;
  private final CurrentUserUtil currentUserUtil;

  @Override
  public Page<FormResponse> getAllForms(FormParam param, Pageable pageable) {
    Page<Form> formPage;

    String search = param.getSearch();
    String createdByStr = param.getCreatedBy();
    if (createdByStr != null && !createdByStr.isBlank()) {
      UUID createdBy = null;
      try {
        createdBy = UUID.fromString(createdByStr);
      } catch (Exception ignored) {
      }
      if (createdBy != null) {
        if (search != null && !search.isBlank()) {
          formPage = formRepository.findByCreatedBy_IdAndNameContainingIgnoreCase(createdBy, search,
              pageable);
        } else {
          formPage = formRepository.findByCreatedBy_Id(createdBy, pageable);
        }
      } else {
        // fallback to normal search if invalid UUID
        formPage = (search != null && !search.isBlank())
            ? formRepository.findByNameContainingIgnoreCase(search, pageable)
            : formRepository.findAll(pageable);
      }
    } else {
      formPage = (search != null && !search.isBlank())
          ? formRepository.findByNameContainingIgnoreCase(search, pageable)
          : formRepository.findAll(pageable);
    }

    return formPage.map(formMapper::toResponse);
  }

  @Override
  @Transactional
  public FormDetailResponse getFormById(UUID id) {
    Form form = findByIdWithFetch(id);
    return formMapper.toDetailResponse(form);
  }

  @Override
  @Transactional
  public FormResponse createForm(FormRequest formRequest) {
    // Synchronous end-to-end processing: only return when fully processed (success/failure)
    FormResponse initial = createFormInitial(formRequest);
    try {
      // Run heavy processing inline (not async)
      processFormAsync(initial.getId(), formRequest);
      // Reload and return
      Form updated = formRepository.findById(initial.getId())
          .orElseThrow(() -> new ResourceNotFoundException("Form", "id", initial.getId()));
      return formMapper.toResponse(updated);
    } catch (Exception e) {
      // Mark failed and rethrow for controller/global handler
      markFormAsFailed(initial.getId(), e.getMessage());
      throw e;
    }
  }

  @Override
  @Transactional
  public FormResponse createFormInitial(FormRequest formRequest) {
    log.info("Creating initial form for URL: {}", formRequest.getEditLink());

    // Phase 1: Fast operations - basic validation and form creation
    Form form = formMapper.toEntity(formRequest);
    currentUserUtil.getCurrentUserIfPresent().ifPresent(form::setCreatedBy);

    // Set initial status as PROCESSING
    form.setStatus(FormStatusEnum.PROCESSING);

    // Use a temporary name if not provided
    if (form.getName() == null || form.getName().trim().isEmpty()) {
      form.setName("Form (Processing...)");
    }

    Form savedForm = formRepository.save(form);

    // Create initial statistics
    FormStatistic statistic = FormStatistic.builder().form(savedForm).totalSurvey(0)
        .completedSurvey(0).failedSurvey(0).errorQuestion(0).build();
    formStatisticRepository.save(statistic);

    log.info("Initial form created with ID: {}, starting async processing", savedForm.getId());
    return formMapper.toResponse(savedForm);
  }

  @Override
  @Transactional
  public void processFormAsync(UUID formId, FormRequest formRequest) {
    log.info("Starting async processing for form ID: {}", formId);

    try {
      Form form = formRepository.findById(formId)
          .orElseThrow(() -> new ResourceNotFoundException("Form", "id", formId));

      // Phase 2: Heavy operations - parsing and encoding
      GoogleFormService.FormExtractionResult formData =
          googleFormService.extractFormData(formRequest.getEditLink());

      // Update form name with extracted title
      if (form.getName().endsWith("(Processing...)")) {
        String baseTitle = formData.getTitle();
        if (baseTitle == null || baseTitle.trim().isEmpty()) {
          baseTitle = "Form";
        }

        final String finalBaseTitle = baseTitle;
        long existingCount = currentUserUtil.getCurrentUserIdIfPresent()
            .map(userId -> formRepository.countByCreatedBy_IdAndNameStartingWithIgnoreCase(userId,
                finalBaseTitle))
            .orElseGet(() -> formRepository.countByNameStartingWithIgnoreCase(finalBaseTitle));

        long nextOrdinal = existingCount + 1;
        form.setName(baseTitle + " #" + nextOrdinal);
        log.info("Updated form name to: {} (existing: {}, next: {})", form.getName(), existingCount,
            nextOrdinal);
      }

      // Save questions from extracted data
      List<ExtractedQuestion> extractedQuestions = formData.getQuestions();
      extractedQuestions.forEach(q -> {
        Question question = Question.builder().form(form).title(q.getTitle())
            .description(q.getDescription()).type(q.getType()).required(q.isRequired())
            .position(q.getPosition()).additionalData(q.getAdditionalData()).build();

        Question savedQuestion = questionRepository.save(question);

        // Save options based on question type
        if ("checkbox_grid".equals(q.getType()) || "multiple_choice_grid".equals(q.getType())) {
          Map<String, Boolean> rowValueMap = new java.util.HashMap<>();
          Map<String, Boolean> subOptionValueMap = new java.util.HashMap<>();
          for (var rowOption : q.getOptions()) {
            if (rowOption.getValue() == null || rowValueMap.containsKey(rowOption.getValue())) {
              continue;
            }
            rowValueMap.put(rowOption.getValue(), true);
            QuestionOption row = QuestionOption.builder().question(savedQuestion)
                .text(rowOption.getText()).value(rowOption.getValue())
                .position(rowOption.getPosition()).isRow(true).build();
            QuestionOption savedRow = optionRepository.save(row);

            if (rowOption.getSubOptions() != null) {
              for (var subOption : rowOption.getSubOptions()) {
                if (subOption.getValue() == null
                    || subOptionValueMap.containsKey(subOption.getValue())) {
                  continue;
                }
                subOptionValueMap.put(subOption.getValue(), true);
                QuestionOption option = QuestionOption.builder().question(savedQuestion)
                    .text(subOption.getText()).value(subOption.getValue())
                    .position(subOption.getPosition()).parentOption(savedRow).isRow(false).build();
                optionRepository.save(option);
              }
            }
          }
        } else {
          q.getOptions().forEach(option -> {
            QuestionOption questionOption =
                QuestionOption.builder().question(savedQuestion).text(option.getText())
                    .value(option.getValue()).position(option.getPosition()).build();
            optionRepository.save(questionOption);
          });
        }
      });

      // Mark as completed
      form.setStatus(FormStatusEnum.CREATED);
      formRepository.save(form);

      log.info("Async form processing completed successfully for form ID: {}", formId);

    } catch (Exception e) {
      log.error("Error during async form processing for form ID: {}: {}", formId, e.getMessage(),
          e);
      throw e; // Re-throw to be caught by caller
    }
  }

  @Override
  @Transactional
  public void markFormAsFailed(UUID formId, String errorMessage) {
    try {
      Form form = formRepository.findById(formId)
          .orElseThrow(() -> new ResourceNotFoundException("Form", "id", formId));

      form.setStatus(FormStatusEnum.FAILED);
      form.setName(form.getName().replace("(Processing...)", "(Failed)"));
      formRepository.save(form);

      log.error("Marked form {} as failed: {}", formId, errorMessage);
    } catch (Exception e) {
      log.error("Error marking form {} as failed: {}", formId, e.getMessage(), e);
    }
  }

  @Override
  public FormResponse updateForm(UUID formId, FormRequest formRequest) {
    return null;
  }

  @Override
  @Transactional
  public void deleteForm(UUID id) {
    log.info("Starting to delete form with ID: {}", id);

    try {
      Form form = formRepository.findById(id)
          .orElseThrow(() -> new ResourceNotFoundException("Form", "id", id));

      // Delete fill requests and related data first
      List<FillRequest> fillRequests = fillRequestRepository.findByForm(form);
      fillRequests.forEach(request -> {
        fillRequestMappingRepository.deleteByFillRequestId(request.getId());
        request.getAnswerDistributions().clear();
      });
      fillRequestRepository.deleteByForm(form);

      // Delete all question options
      List<Question> questions = questionRepository.findByForm(form);
      questions.forEach(question -> {
        optionRepository.deleteByQuestion(question);
      });

      // Delete questions
      questionRepository.deleteByForm(form);

      // Delete form statistics
      formStatisticRepository.findByFormId(id).ifPresent(formStatisticRepository::delete);

      // Finally delete the form
      formRepository.delete(form);

      log.info("Successfully deleted form {}", id);
    } catch (Exception e) {
      log.error("Error deleting form: {}. Error: {}", id, e.getMessage());
      throw new RuntimeException("Failed to delete form: " + e.getMessage(), e);
    }
  }

  @Override
  public Form findByIdWithFetch(UUID id) {
    Form form = formRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Form", "id", id));
    // Fetch the questions and options
    List<Question> questionDbs = questionRepository.findByForm(form);
    for (Question question : form.getQuestions()) {
      question.setOptions(
          questionDbs.stream().filter(q -> q.getId().equals(question.getId())).findFirst()
              .orElseThrow(() -> new ResourceNotFoundException("Question", "id", question.getId()))
              .getOptions());
    }
    // Fetch fill requests with eager loading of answer distributions
    List<FillRequest> fillRequestDbs = fillRequestRepository.findByForm(form);
    for (FillRequest fillRequest : form.getFillRequests()) {
      // Use findByIdWithAllData to get AnswerDistributions with eager loading
      FillRequest fillRequestWithData =
          fillRequestRepository.findByIdWithAllData(fillRequest.getId()).orElseThrow(
              () -> new ResourceNotFoundException("FillRequest", "id", fillRequest.getId()));
      fillRequest.setAnswerDistributions(fillRequestWithData.getAnswerDistributions());
    }
    // Sort
    SortUtil.sortByPosition(form);
    SortUtil.sortFillRequestsByCreatedAt(form);

    return form;
  }

  @Override
  public java.util.List<FormResponse> getAllFormsByUserId(UUID userId) {
    log.info("Getting all forms for user: {}", userId);

    List<Form> forms = formRepository.findByCreatedBy_IdOrderByCreatedAtDesc(userId);
    return forms.stream().map(formMapper::toResponse).collect(java.util.stream.Collectors.toList());
  }

}
