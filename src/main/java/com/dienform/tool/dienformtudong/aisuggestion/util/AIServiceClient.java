package com.dienform.tool.dienformtudong.aisuggestion.util;

import java.util.Map;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AnswerAttributesRequest;
import com.dienform.tool.dienformtudong.aisuggestion.dto.response.AnswerAttributesResponse;

/**
 * Interface for AI Service Client Defines contract for AI service communication
 */
public interface AIServiceClient {

  /**
   * Validate AI service availability
   *
   * @return true if service is available
   */
  boolean isServiceAvailable();

  /**
   * Get remaining token quota
   *
   * @return remaining tokens for current period
   */
  Long getRemainingTokens();

  /**
   * Get service health status
   *
   * @return health status information
   */
  Map<String, Object> getHealthStatus();

  /**
   * Get service configuration
   *
   * @return current service configuration
   */
  Map<String, Object> getServiceConfiguration();

  /**
   * Generate answer attributes for all questions in a form
   *
   * @param request The answer attributes request
   * @param formData Form structure data
   * @return The generated answer attributes response
   */
  AnswerAttributesResponse generateAnswerAttributes(AnswerAttributesRequest request,
      Map<String, Object> formData);
}
