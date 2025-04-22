package com.dienform.tool.dienformtudong.form.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.exception.ResourceNotFoundException;
import com.dienform.common.util.Constants;
import com.dienform.tool.dienformtudong.form.dto.request.FormRequest;
import com.dienform.tool.dienformtudong.form.dto.response.FormDetailResponse;
import com.dienform.tool.dienformtudong.form.dto.response.FormResponse;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.enums.FormStatusEnum;
import com.dienform.tool.dienformtudong.form.mapper.FormMapper;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.form.service.FormService;
import com.dienform.tool.dienformtudong.formstatistic.dto.response.FormStatisticResponse;
import com.dienform.tool.dienformtudong.formstatistic.entity.FormStatistic;
import com.dienform.tool.dienformtudong.formstatistic.repository.FormStatisticRepository;
import com.dienform.tool.dienformtudong.question.dto.response.QuestionResponse;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import com.dienform.tool.dienformtudong.question.service.QuestionService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FormServiceImpl implements FormService {

    private final FormMapper formMapper;
    private final FormRepository formRepository;
    private final FormStatisticRepository statisticRepository;
    private final QuestionService questionService;

    @Override
    public Page<FormResponse> getAllForms(String search, Pageable pageable) {
        Page<Form> formPage;

        if (search != null && !search.trim().isEmpty()) {
            formPage = formRepository.findByNameContainingIgnoreCase(search, pageable);
        } else {
            formPage = formRepository.findAll(pageable);
        }

        return null;
    }

    @Override
    public FormDetailResponse getFormById(UUID id) {
        Form form = formRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Form", "id", id));

        List<QuestionResponse> questions = questionService.getQuestionsByFormId(id);
        FormStatisticResponse statistics = getFormStatistics(id);

        return null;
    }

    @Override
    @Transactional
    public FormResponse createForm(FormRequest formRequest) {
        // Create and save the form
        Form form = formMapper.toEntity(formRequest);

        Form savedForm = formRepository.save(form);

        // Create initial statistics
        // FormStatistic statistic = FormStatistic.builder().formId(savedForm.getId()).totalSurvey(0)
        //         .completedSurvey(0).failedSurvey(0).errorQuestion(0).build();

        // statisticRepository.save(statistic);

        // // Extract and save questions from Google Form
        // List<Question> extractedQuestions = new ArrayList<>();
        // questionRepository.saveAll(extractedQuestions);

        // // Get the questions as DTOs
        // List<QuestionResponse> questions = questionService.getQuestionsByFormId(savedForm.getId());

        // // Create response
        // FormStatisticResponse statisticResponse = mapToFormStatisticResponse(statistic);

        return formMapper.toResponse(savedForm);
    }

    @Override
    @Transactional
    public void deleteForm(UUID id) {
        if (!formRepository.existsById(id)) {
            throw new ResourceNotFoundException("Form", "id", id);
        }

        formRepository.deleteById(id);
    }

    // private FormResponse mapToFormResponse(Form form) {
    //     FormStatisticResponse statistics = getFormStatistics(form.getId());

    //     return FormResponse.builder().id(form.getId()).name(form.getName()).status(form.getStatus())
    //             .createdAt(form.getCreatedAt()).statistics(statistics).build();
    // }

    // private FormDetailResponse mapToFormDetailResponse(Form form, List<QuestionResponse> questions,
    //         FormStatisticResponse statistics) {
    //     return FormDetailResponse.builder().id(form.getId()).name(form.getName())
    //             .editLink(form.getEditLink()).createdAt(form.getCreatedAt())
    //             .status(form.getStatus()).questions(questions).statistics(statistics).build();
    // }

    private FormStatisticResponse getFormStatistics(UUID formId) {
        return statisticRepository.findByFormId(formId).map(this::mapToFormStatisticResponse)
                .orElse(FormStatisticResponse.builder().totalSurvey(0).completedSurvey(0)
                        .failedSurvey(0).errorQuestion(0).lastUpdatedAt(LocalDateTime.now())
                        .build());
    }

    private FormStatisticResponse mapToFormStatisticResponse(FormStatistic statistic) {
        return FormStatisticResponse.builder().id(statistic.getId())
                .totalSurvey(statistic.getTotalSurvey())
                .completedSurvey(statistic.getCompletedSurvey())
                .failedSurvey(statistic.getFailedSurvey())
                .errorQuestion(statistic.getErrorQuestion())
//                .lastUpdatedAt(statistic.getLastUpdatedAt())
            .build();
    }
}
