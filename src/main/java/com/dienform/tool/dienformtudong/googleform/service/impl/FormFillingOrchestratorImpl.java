package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.googleform.service.FormFillingOrchestrator;
import com.dienform.tool.dienformtudong.googleform.service.FormStructureAnalyzer;
import com.dienform.tool.dienformtudong.googleform.service.SectionAwareFormFiller;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of FormFillingOrchestrator that coordinates form filling logic
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FormFillingOrchestratorImpl implements FormFillingOrchestrator {

  private final FormStructureAnalyzer formStructureAnalyzer;
  private final SectionAwareFormFiller sectionAwareFormFiller;
  private final FormRepository formRepository;

  @Override
  public boolean orchestrateFormFill(UUID fillRequestId, UUID formId, String formUrl,
      Map<Question, QuestionOption> selections, boolean humanLike,
      FormFillMethod existingFillMethod) {

    log.info("Orchestrating form fill for request: {} with {} questions", fillRequestId,
        selections.size());

    try {
      // Get form and analyze structure
      Form form = formRepository.findById(formId)
          .orElseThrow(() -> new RuntimeException("Form not found: " + formId));

      // Check if form has sections
      boolean hasSections = formStructureAnalyzer.hasSections(form);

      if (!hasSections) {
        log.info("Form {} is single-section, using existing fill logic", formId);
        return existingFillMethod.fill(fillRequestId, formId, formUrl, selections, humanLike);
      }

      // Form has sections, use section-aware logic
      log.info("Form {} has sections, using section-aware fill logic", formId);
      return sectionAwareFormFiller.fillFormWithSections(fillRequestId, formId, formUrl, selections,
          humanLike);

    } catch (Exception e) {
      log.error("Error in form filling orchestration for request {}: {}", fillRequestId,
          e.getMessage(), e);

      // Fallback to existing logic if section-aware logic fails
      log.info("Falling back to existing fill logic for request: {}", fillRequestId);
      try {
        return existingFillMethod.fill(fillRequestId, formId, formUrl, selections, humanLike);
      } catch (Exception fallbackError) {
        log.error("Fallback fill logic also failed for request {}: {}", fillRequestId,
            fallbackError.getMessage());
        return false;
      }
    }
  }
}

