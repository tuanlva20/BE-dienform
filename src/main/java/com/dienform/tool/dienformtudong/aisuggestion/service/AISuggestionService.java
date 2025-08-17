package com.dienform.tool.dienformtudong.aisuggestion.service;

import java.util.Map;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AISuggestionRequest;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AnswerAttributesRequest;
import com.dienform.tool.dienformtudong.aisuggestion.dto.response.AnswerAttributesResponse;

/**
 * Service interface for AI Suggestion functionality Provides methods for generating answer
 * attributes and validating requests
 */
public interface AISuggestionService {

  /**
   * Validate request before processing
   *
   * @param request The AI suggestion request
   * @return Validation result
   */
  Map<String, Object> validateRequest(AISuggestionRequest request);

  /**
   * Generate answer attributes for all questions in a form
   *
   * @param request The answer attributes request
   * @return The generated answer attributes response
   */
  AnswerAttributesResponse generateAnswerAttributes(AnswerAttributesRequest request);
}
