package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.googleform.service.FormFillingOrchestrator;
import com.dienform.tool.dienformtudong.googleform.service.FormStructureAnalyzer;
import com.dienform.tool.dienformtudong.googleform.service.SectionAwareFormFiller;
import com.dienform.tool.dienformtudong.googleform.service.SectionNavigationService;
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
  private final SectionNavigationService sectionNavigationService;
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

      // Form has sections, try section-aware logic first
      log.info("Form {} has sections, trying section-aware fill logic", formId);
      try {
        boolean success = sectionAwareFormFiller.fillFormWithSections(fillRequestId, formId,
            formUrl, selections, humanLike);
        if (success) {
          log.info("Section-aware form filling completed successfully for request: {}",
              fillRequestId);
          return true;
        } else {
          log.warn("Section-aware form filling failed for request: {}, trying fallback",
              fillRequestId);
        }
      } catch (Exception sectionAwareError) {
        log.warn("Section-aware form filling threw exception for request {}: {}", fillRequestId,
            sectionAwareError.getMessage());
      }

      // Fallback to SectionNavigationService for multi-section forms
      log.info("Trying SectionNavigationService fallback for request: {}", fillRequestId);
      try {
        boolean success = sectionNavigationService.fillSections(formUrl, selections, humanLike);
        if (success) {
          log.info("SectionNavigationService fallback completed successfully for request: {}",
              fillRequestId);
          return true;
        } else {
          log.warn("SectionNavigationService fallback failed for request: {}", fillRequestId);
        }
      } catch (Exception navigationError) {
        log.warn("SectionNavigationService fallback threw exception for request {}: {}",
            fillRequestId, navigationError.getMessage());
      }

      // Final fallback to existing logic
      log.info("Falling back to existing fill logic for request: {}", fillRequestId);
      try {
        return existingFillMethod.fill(fillRequestId, formId, formUrl, selections, humanLike);
      } catch (Exception fallbackError) {
        log.error("All form filling methods failed for request {}: {}", fillRequestId,
            fallbackError.getMessage());
        return false;
      }

    } catch (Exception e) {
      log.error("Error in form filling orchestration for request {}: {}", fillRequestId,
          e.getMessage(), e);

      // Final fallback to existing logic if orchestration fails
      log.info("Orchestration failed, trying final fallback to existing fill logic for request: {}",
          fillRequestId);
      try {
        return existingFillMethod.fill(fillRequestId, formId, formUrl, selections, humanLike);
      } catch (Exception fallbackError) {
        log.error("Final fallback fill logic also failed for request {}: {}", fillRequestId,
            fallbackError.getMessage());
        return false;
      }
    }
  }
}

