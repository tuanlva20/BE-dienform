package com.dienform.tool.dienformtudong.question.service;

import java.util.List;
import java.util.UUID;
import com.dienform.tool.dienformtudong.question.dto.response.QuestionResponse;

public interface QuestionService {
    List<QuestionResponse> getQuestionsByFormId(UUID formId);
    QuestionResponse getQuestionById(UUID id);
}