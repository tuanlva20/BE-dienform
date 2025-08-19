package com.dienform.tool.dienformtudong.googleform.service;

import java.util.Map;
import java.util.UUID;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;

/**
 * Service orchestrator that coordinates between section-aware and existing form filling logic
 */
public interface FormFillingOrchestrator {

  /**
   * Functional interface for existing form fill methods
   */
  @FunctionalInterface
  interface FormFillMethod {
    boolean fill(UUID fillRequestId, UUID formId, String formUrl,
        Map<Question, QuestionOption> selections, boolean humanLike);
  }

  /**
   * Orchestrate form filling by determining whether to use section-aware or existing logic
   * 
   * @param fillRequestId The ID of the fill request
   * @param formId The ID of the form
   * @param formUrl The URL of the form to fill
   * @param selections Map of questions to selected options
   * @param humanLike Whether to simulate human-like behavior
   * @param existingFillMethod The existing fill method to use as fallback
   * @return true if successful, false otherwise
   */
  boolean orchestrateFormFill(UUID fillRequestId, UUID formId, String formUrl,
      Map<Question, QuestionOption> selections, boolean humanLike,
      FormFillMethod existingFillMethod);
}

