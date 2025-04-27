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
import com.dienform.tool.dienformtudong.question.service.QuestionService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FormServiceImpl implements FormService {

  private static final Logger log = LoggerFactory.getLogger(FormServiceImpl.class);
  private final FormMapper formMapper;
  private final FormRepository formRepository;
  private final FormStatisticRepository statisticRepository;
  private final QuestionService questionService;
  private final GoogleFormService googleFormService;
  private final QuestionRepository questionRepository;
  private final QuestionOptionRepository optionRepository;
  private final FillRequestRepository fillRequestRepository;

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
      Question question =
          Question.builder().form(savedForm).title(q.getTitle()).description(q.getDescription())
              .type(q.getType()).required(q.isRequired()).position(q.getPosition()).build();
      // Save the question to the repository
      questionRepository.save(question);

      // Save the options for the question
      q.getOptions().forEach(option -> {
        QuestionOption questionOption = QuestionOption.builder().question(question)
            .text(option.getText()).value(option.getValue()).position(option.getPosition()).build();
        optionRepository.save(questionOption);
      });
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
    if (!formRepository.existsById(id)) {
      throw new ResourceNotFoundException("Form", "id", id);
    }

    formRepository.deleteById(id);
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

    return form;
  }
}
