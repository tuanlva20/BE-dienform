package com.dienform.tool.dienformtudong.question.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.exception.ResourceNotFoundException;
import com.dienform.tool.dienformtudong.question.dto.response.QuestionOptionResponse;
import com.dienform.tool.dienformtudong.question.dto.response.QuestionResponse;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import com.dienform.tool.dienformtudong.question.repository.QuestionOptionRepository;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import com.dienform.tool.dienformtudong.question.service.QuestionService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {

        private final QuestionRepository questionRepository;
        private final QuestionOptionRepository optionRepository;

        @Override
        @Transactional(readOnly = true)
        public List<QuestionResponse> getQuestionsByFormId(UUID formId) {
                List<Question> questions = questionRepository.findByFormIdOrderByPosition(formId);
                return questions.stream().map(this::mapToQuestionResponse)
                                .collect(Collectors.toList());
        }

        @Override
        @Transactional(readOnly = true)
        public QuestionResponse getQuestionById(UUID id) {
                Question question = questionRepository.findById(id).orElseThrow(
                                () -> new ResourceNotFoundException("Question", "id", id));

                return mapToQuestionResponse(question);
        }

        private QuestionResponse mapToQuestionResponse(Question question) {
                List<QuestionOption> options =
                                optionRepository.findByQuestionIdOrderByPosition(question.getId());

                // Remove duplicates and map to responses
                List<QuestionOptionResponse> optionResponses = options.stream()
                                .collect(Collectors.toMap(QuestionOption::getValue,
                                                option -> option,
                                                (existing, replacement) -> existing))
                                .values().stream().map(this::mapToOptionResponse)
                                .collect(Collectors.toList());

                return QuestionResponse.builder().id(question.getId()).title(question.getTitle())
                                .description(question.getDescription()).type(question.getType())
                                .required(question.getRequired())
                                .position(question.getPosition() != null ? question.getPosition()
                                                : 0)
                                .options(optionResponses)
                                .additionalData(question.getAdditionalData()).build();
        }

        private QuestionOptionResponse mapToOptionResponse(QuestionOption option) {
                // Set row=true if value starts with "row_"
                boolean isRow = option.getValue() != null && option.getValue().startsWith("row_");

                if (isRow) {
                        // For grid rows, collect all column options
                        List<QuestionOption> subOptions =
                                        option.getSubOptions() == null ? Collections.emptyList()
                                                        : option.getSubOptions();
                        List<String> columnOptions = subOptions.stream()
                                        .map(QuestionOption::getText).collect(Collectors.toList());

                        return QuestionOptionResponse.builder().id(option.getId())
                                        .text(option.getText()).value(option.getValue())
                                        .position(option.getPosition() != null
                                                        ? option.getPosition()
                                                        : 0)
                                        .isRow(true).columnOptions(columnOptions).build();
                }

                // For non-grid options
                return QuestionOptionResponse.builder().id(option.getId()).text(option.getText())
                                .value(option.getValue())
                                .position(option.getPosition() != null ? option.getPosition() : 0)
                                .isRow(false).build();
        }
}
