package com.dienform.tool.dienformtudong.form.service.impl;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.exception.ResourceNotFoundException;
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
  private final FormStatisticRepository statisticRepository;
  private final GoogleFormService googleFormService;
  private final QuestionRepository questionRepository;
  private final QuestionOptionRepository optionRepository;
  private final FillRequestRepository fillRequestRepository;
  private final FillRequestMappingRepository fillRequestMappingRepository;

  @Override
  public Page<FormResponse> getAllForms(FormParam param, Pageable pageable) {
    Page<Form> formPage;

    if (param.getSearch() != null && !param.getSearch().isEmpty()) {
      formPage = formRepository.findByNameContainingIgnoreCase(param.getSearch(), pageable);
    } else {
      formPage = formRepository.findAll(pageable);
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
    // Create and save the form
    Form form = formMapper.toEntity(formRequest);

    // If form name is null or empty, extract title from Google Form and append form count
    if (form.getName() == null || form.getName().trim().isEmpty()) {
      // Convert edit link to public link first
      String title = googleFormService.extractTitleFromFormLink(form.getEditLink());

      // Get total form count
      long formCount = formRepository.count();

      if (title != null && !title.trim().isEmpty()) {
        // Append form count to title
        form.setName(title + " #" + (formCount + 1));
        log.info("Using extracted title with count as form name: {}", form.getName());
      } else {
        // Use default name with count if title extraction fails
        form.setName("Form #" + (formCount + 1));
        log.warn("Could not extract title from Google Form, using default name with count: {}",
            form.getName());
      }
    }

    form.setStatus(FormStatusEnum.CREATED);
    Form savedForm = formRepository.save(form);

    // Create initial statistics
    FormStatistic statistic = FormStatistic.builder().form(savedForm).totalSurvey(0)
        .completedSurvey(0).failedSurvey(0).errorQuestion(0).build();
    statisticRepository.save(statistic);

    // Extract and save questions from Google Form
    List<ExtractedQuestion> extractedQuestions =
        googleFormService.readGoogleForm(formRequest.getEditLink());
    extractedQuestions.forEach(q -> {
      Question question = Question.builder().form(savedForm).title(q.getTitle())
          .description(q.getDescription()).type(q.getType()).required(q.isRequired())
          .position(q.getPosition()).additionalData(q.getAdditionalData()).build();

      // Save the question to the repository
      Question savedQuestion = questionRepository.save(question);

      // Save options based on question type
      if ("checkbox_grid".equals(q.getType()) || "multiple_choice_grid".equals(q.getType())) {
        // For grid questions, save row options with their sub-options
        q.getOptions().forEach(rowOption -> {
          QuestionOption row = QuestionOption.builder().question(savedQuestion)
              .text(rowOption.getText()).value(rowOption.getValue())
              .position(rowOption.getPosition()).isRow(true).build();
          QuestionOption savedRow = optionRepository.save(row);

          // Save sub-options for each row
          if (rowOption.getSubOptions() != null) {
            rowOption.getSubOptions().forEach(subOption -> {
              QuestionOption option = QuestionOption.builder().question(savedQuestion)
                  .text(subOption.getText()).value(subOption.getValue())
                  .position(subOption.getPosition()).parentOption(savedRow).build();
              optionRepository.save(option);
            });
          }
        });
      } else {
        // For regular questions, save options normally
        q.getOptions().forEach(option -> {
          QuestionOption questionOption =
              QuestionOption.builder().question(savedQuestion).text(option.getText())
                  .value(option.getValue()).position(option.getPosition()).build();
          optionRepository.save(questionOption);
        });
      }
    });

    return formMapper.toResponse(savedForm);
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
      statisticRepository.findByFormId(id).ifPresent(statisticRepository::delete);

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
    // Fetch fill requests
    List<FillRequest> fillRequestDbs = fillRequestRepository.findByForm(form);
    for (FillRequest fillRequest : form.getFillRequests()) {
      fillRequest.setAnswerDistributions(
          fillRequestDbs.stream().filter(f -> f.getId().equals(fillRequest.getId())).findFirst()
              .orElseThrow(
                  () -> new ResourceNotFoundException("FillRequest", "id", fillRequest.getId()))
              .getAnswerDistributions());
    }
    // Sort
    SortUtil.sortByPosition(form);
    SortUtil.sortFillRequestsByCreatedAt(form);

    return form;
  }

}
