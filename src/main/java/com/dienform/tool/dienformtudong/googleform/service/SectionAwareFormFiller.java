package com.dienform.tool.dienformtudong.googleform.service;

import java.util.Map;
import java.util.UUID;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;

/**
 * Service for filling forms with section-aware logic
 */
public interface SectionAwareFormFiller {

  /**
   * Fill form with section-aware logic using information from database
   * 
   * @param fillRequestId The ID of the fill request
   * @param formId The ID of the form
   * @param formUrl The URL of the form to fill
   * @param selections Map of questions to selected options
   * @param humanLike Whether to simulate human-like behavior
   * @return true if successful, false otherwise
   */
  boolean fillFormWithSections(UUID fillRequestId, UUID formId, String formUrl,
      Map<Question, QuestionOption> selections, boolean humanLike);
}

