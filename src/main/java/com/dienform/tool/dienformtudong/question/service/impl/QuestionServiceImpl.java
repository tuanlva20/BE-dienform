package com.dienform.tool.dienformtudong.question.service.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
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
        public List<QuestionResponse> getQuestionsByFormId(UUID formId) {
                List<Question> questions = questionRepository.findByFormIdOrderByPosition(formId);
                return questions.stream().map(this::mapToQuestionResponse)
                                .collect(Collectors.toList());
        }

        @Override
        public QuestionResponse getQuestionById(UUID id) {
                Question question = questionRepository.findById(id).orElseThrow(
                                () -> new ResourceNotFoundException("Question", "id", id));

                return mapToQuestionResponse(question);
        }

        private QuestionResponse mapToQuestionResponse(Question question) {
                List<QuestionOption> options =
                                optionRepository.findByQuestionIdOrderByPosition(question.getId());

                List<QuestionOptionResponse> optionResponses = options.stream()
                                .map(option -> QuestionOptionResponse.builder().id(option.getId())
                                                .text(option.getText()).value(option.getValue())
                                                .position(option.getPosition()).build())
                                .collect(Collectors.toList());

                return QuestionResponse.builder().id(question.getId()).title(question.getTitle())
                                .description(question.getDescription()).type(question.getType())
                                .required(question.getRequired())
                                .position(question.getPosition() != null ? question.getPosition()
                                                : 0)
                                .options(optionResponses).build();
        }
}
