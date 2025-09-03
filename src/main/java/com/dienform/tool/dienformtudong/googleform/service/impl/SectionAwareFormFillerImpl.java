package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.dienform.common.exception.ResourceNotFoundException;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.googleform.dto.FormStructure;
import com.dienform.tool.dienformtudong.googleform.dto.FormStructureType;
import com.dienform.tool.dienformtudong.googleform.dto.QuestionInfo;
import com.dienform.tool.dienformtudong.googleform.dto.SectionInfo;
import com.dienform.tool.dienformtudong.googleform.service.FormFillingHelper;
import com.dienform.tool.dienformtudong.googleform.service.FormStructureAnalyzer;
import com.dienform.tool.dienformtudong.googleform.service.RequiredQuestionAutofillService;
import com.dienform.tool.dienformtudong.googleform.service.SectionAwareFormFiller;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of SectionAwareFormFiller that handles form filling with section navigation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SectionAwareFormFillerImpl implements SectionAwareFormFiller {

  // Reuse existing selectors from SectionNavigationService
  private static final By NEXT_BUTTON = By.xpath(
      "//div[@role='button' and (.//span[normalize-space()='Tiếp'] or .//span[normalize-space()='Next'])]");
  private static final By SUBMIT_BUTTON = By.xpath(
      "//div[@role='button' and (.//span[contains(text(), 'Gửi')] or .//span[contains(text(), 'Submit')] or @aria-label='Submit')]");
  private final FormStructureAnalyzer formStructureAnalyzer;

  private final FormRepository formRepository;
  private final FormFillingHelper formFillingHelper;
  private final RequiredQuestionAutofillService requiredQuestionAutofillService;
  private final FillRequestRepository fillRequestRepository;
  private final com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestMappingRepository fillRequestMappingRepository;

  @Value("${google.form.timeout-seconds:30}")
  private int timeoutSeconds;

  @Value("${google.form.headless:true}")
  private boolean headless;

  @Value("${google.form.auto-submit:true}")
  private boolean autoSubmitEnabled;

  @Value("${google.form.submit-when-visible:false}")
  private boolean submitWhenVisible;

  // Store the current fill request ID for "other" text lookup
  private UUID currentFillRequestId;

  // ThreadLocal to store "other" text mapping for questions, similar to GoogleFormServiceImpl
  private final ThreadLocal<Map<UUID, String>> dataFillOtherTextByQuestion = new ThreadLocal<>();

  // Track per-driver temporary Chrome profile directories for cleanup
  private final Map<WebDriver, Path> driverProfileDirMap = new ConcurrentHashMap<>();

  @Override
  public boolean fillFormWithSections(UUID fillRequestId, UUID formId, String formUrl,
      Map<Question, QuestionOption> selections, boolean humanLike) {

    log.info("Starting section-aware form fill for request: {} with {} questions", fillRequestId,
        selections.size());

    // Store the fill request ID for "other" text lookup
    this.currentFillRequestId = fillRequestId;

    // Set up "other" text mapping from AnswerDistribution
    try {
      Map<UUID, String> localOtherText = new HashMap<>();
      // Use findByIdWithAllData to avoid LazyInitializationException
      FillRequest fillRequest =
          fillRequestRepository.findByIdWithAllData(fillRequestId).orElse(null);
      if (fillRequest != null && fillRequest.getAnswerDistributions() != null) {
        for (AnswerDistribution dist : fillRequest.getAnswerDistributions()) {

          if (dist.getQuestion() != null && dist.getOption() != null
              && "__other_option__".equalsIgnoreCase(dist.getOption().getValue())
              && dist.getValueString() != null && !dist.getValueString().trim().isEmpty()) {
            localOtherText.put(dist.getQuestion().getId(), dist.getValueString().trim());
          }
        }
      }
      // Merge per-submission selections to capture explicit other text like "__other_option__-text"
      try {
        if (selections != null && !selections.isEmpty()) {
          for (Map.Entry<Question, QuestionOption> e : selections.entrySet()) {
            try {
              Question q = e.getKey();
              QuestionOption opt = e.getValue();
              if (q == null || opt == null)
                continue;
              String t = opt.getText();
              if (t == null)
                continue;
              int di = t.lastIndexOf('-');
              if (di > 0) {
                String before = t.substring(0, di).trim();
                String after = t.substring(di + 1).trim();
                if (!after.isEmpty()
                    && ("__other_option__".equalsIgnoreCase(before) || before.matches("\\d+"))) {
                  localOtherText.put(q.getId(), after);
                }
              }
            } catch (Exception ignore) {
            }
          }
        }
      } catch (Exception mergeIgnore) {
        log.debug("Failed merging per-submission selections for other text: {}",
            mergeIgnore.getMessage());
      }

      dataFillOtherTextByQuestion.set(localOtherText);
    } catch (Exception e) {
      log.warn("Failed to set up 'Other' text mapping: {}", e.getMessage(), e);
    }

    // Debug: Log all selections
    log.info("Selections to fill:");
    selections.forEach((question, option) -> {
      log.info("  Question: '{}' (type: {}) -> Option: '{}'", question.getTitle(),
          question.getType(), option.getText());
    });

    try {
      // 1. Get form and analyze structure from database
      Form form = formRepository.findById(formId)
          .orElseThrow(() -> new ResourceNotFoundException("Form", "id", formId));

      FormStructure formStructure = formStructureAnalyzer.analyzeFormStructureFromDatabase(form);
      log.info("Form structure analyzed: type={}, sections={}", formStructure.getType(),
          formStructure.getSections().size());

      // Debug: Log section structure
      for (SectionInfo section : formStructure.getSections()) {
        log.info("Section {}: '{}' with {} questions", section.getSectionIndex(),
            section.getSectionTitle(), section.getQuestions().size());
        for (QuestionInfo questionInfo : section.getQuestions()) {
          Question q = questionInfo.getQuestionEntity();
          QuestionOption opt = null;
          try {
            opt = selections.get(q);
            if (opt == null) {
              // fallback to id/key based
              // note: optionById/optionByKey not ready yet here; log with direct lookup
              // to keep side effects minimal
            }
          } catch (Exception ignore) {
          }
          log.info("  - Question: '{}' (type: {}) -> Option: {}", q.getTitle(), q.getType(),
              opt != null ? opt.getText() : "null");
        }
      }

      if (formStructure.getType() == FormStructureType.SINGLE_SECTION) {
        // Use existing logic for single-section form
        log.info("Form {} is single-section, using existing fill logic", formId);
        return executeSingleSectionFill(fillRequestId, formId, formUrl, selections, humanLike);
      }

      // 2. Fill multi-section form
      log.info("Form {} is multi-section with {} sections", formId,
          formStructure.getSections().size());
      return executeMultiSectionFill(fillRequestId, formId, formUrl, selections, humanLike,
          formStructure);
    } catch (Exception e) {
      log.error("Error in fillFormWithSections for request {}: {}", fillRequestId, e.getMessage(),
          e);
      return false;
    } finally {
      // Clear the stored fill request ID and ThreadLocal
      this.currentFillRequestId = null;
      dataFillOtherTextByQuestion.remove();
    }
  }

  /**
   * Execute form fill for single-section form using existing logic
   */
  private boolean executeSingleSectionFill(UUID fillRequestId, UUID formId, String formUrl,
      Map<Question, QuestionOption> selections, boolean humanLike) {
    // For single-section forms, we'll use the existing logic
    // This method should not be called directly, as the orchestrator will handle it
    log.warn("executeSingleSectionFill called directly - this should be handled by orchestrator");
    return false;
  }

  /**
   * Execute form fill for multi-section form
   */
  private boolean executeMultiSectionFill(UUID fillRequestId, UUID formId, String formUrl,
      Map<Question, QuestionOption> selections, boolean humanLike, FormStructure formStructure) {
    WebDriver driver = null;
    try {
      log.info("Starting multi-section form fill for request: {} with {} sections", fillRequestId,
          formStructure.getSections().size());

      driver = openBrowser(formUrl, humanLike);
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

      // Clear autofill tracking for new form
      requiredQuestionAutofillService.clearAutofillTracking();

      // Build robust lookup maps so we don't depend on entity identity equality
      Map<java.util.UUID, QuestionOption> optionById = buildOptionByQuestionId(selections);
      Map<String, QuestionOption> optionByKey = buildOptionByCompositeKey(selections, null);

      // 2.a Fill first page (intro) questions that don't have section_index in additionalData
      int introFilled = fillIntroQuestionsIfAny(driver, selections, humanLike);
      if (introFilled > 0) {
        log.info("Filled {} intro questions (first section without section_index)", introFilled);
        // Move to next section after filling intro if Next is available
        try {
          clickNextButton(driver, wait, humanLike);
          try {
            Thread.sleep(800);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
        } catch (Exception e) {
          log.debug("No Next after intro or click failed: {}", e.getMessage());
        }
      } else {
        // Check if first section has no questions but has Next button
        boolean hasQuestionsInFirstSection = hasQuestionsInCurrentSection(driver);
        boolean hasNextButton = isNextButtonVisible(driver);

        if (!hasQuestionsInFirstSection && hasNextButton) {
          log.info("First section has no questions but has Next button, clicking Next to proceed");
          try {
            clickNextButton(driver, wait, humanLike);
            try {
              Thread.sleep(800);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
            log.info("Successfully clicked Next button to move past empty first section");
          } catch (Exception e) {
            log.warn("Failed to click Next button for empty first section: {}", e.getMessage());
          }
        } else if (!hasQuestionsInFirstSection && !hasNextButton) {
          log.info("First section has no questions and no Next button - single section form");
        } else {
          log.info("First section has questions but none were filled - this may indicate an issue");
        }
      }

      // 3. Fill each section with improved navigation logic
      for (SectionInfo section : formStructure.getSections()) {
        try {
          log.info("Processing section {}: '{}' with {} questions", section.getSectionIndex(),
              section.getSectionTitle(), section.getQuestions().size());

          // Fill questions in current section
          int totalQuestionsInSection = section.getQuestions().size();
          int filledQuestions = fillQuestionsInSection(driver, section, selections, optionById,
              optionByKey, humanLike);

          log.info("Filled {}/{} questions in section {}", filledQuestions, totalQuestionsInSection,
              section.getSectionIndex());

          // Retry logic for incomplete sections
          int maxRetries = 2;
          int retryCount = 0;
          while (filledQuestions < totalQuestionsInSection && retryCount < maxRetries) {
            retryCount++;
            log.info("Retry {} for section {}: filled {}/{} questions", retryCount,
                section.getSectionIndex(), filledQuestions, totalQuestionsInSection);

            // Try to minimally satisfy current page and navigate to the target section, then retry.
            if (filledQuestions == 0) {
              boolean canProceed = false;
              try {
                log.info(
                    "No filled questions for section {} on first attempt. Checking if autofill is needed...",
                    section.getSectionIndex());

                // Check if Next button is already ready before trying autofill
                if (requiredQuestionAutofillService.isNextButtonReady(driver)) {
                  log.info(
                      "Next button is already ready for section {}, no autofill needed in fallback",
                      section.getSectionIndex());
                  canProceed = true;
                } else {
                  log.info("Next button not ready, attempting autofill in fallback for section {}",
                      section.getSectionIndex());
                  canProceed = requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
                }
              } catch (Exception autofillEx) {
                log.warn("Autofill required questions failed for section {}: {}",
                    section.getSectionIndex(), autofillEx.getMessage());
              }

              if (canProceed) {
                clickNextButton(driver, wait, humanLike);
                try {
                  Thread.sleep(800);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }

                // Retry filling now that we likely reached the target section
                filledQuestions = fillQuestionsInSection(driver, section, selections, optionById,
                    optionByKey, humanLike);
                log.info("Filled {}/{} questions in section {} (second attempt after navigation)",
                    filledQuestions, totalQuestionsInSection, section.getSectionIndex());
              } else {
                log.warn(
                    "Unable to navigate to section {} because autofill could not satisfy current page",
                    section.getSectionIndex());
              }
            }

            // Verify all questions in this section are filled before proceeding
            if (filledQuestions < totalQuestionsInSection) {
              log.warn(
                  "Failed to fill all questions in section {} after {} retries. Only {}/{} questions filled.",
                  section.getSectionIndex(), maxRetries, filledQuestions, totalQuestionsInSection);
              // Continue anyway but log the issue
            }
          }

          // Add a small delay between sections for stability
          if (humanLike) {
            try {
              Thread.sleep(500 + new Random().nextInt(500));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }

          // CRITICAL FIX: Improved navigation logic to ensure we reach Submit button
          // Check if Submit button is visible first
          if (isSubmitButtonVisible(driver)) {
            if (submitWhenVisible) {
              log.info("Submit button visible - submitWhenVisible enabled, submitting now");
              clickSubmitButton(driver, wait, humanLike);
              return true;
            } else {
              log.info(
                  "Submit button visible - reached final section, but continuing to process remaining sections");
              // Don't break here, continue processing remaining sections
              // The final section handling will be done after the loop
            }
          }

          // Check if Next button is visible
          if (isNextButtonVisible(driver)) {
            // Ensure we can proceed by satisfying required questions if needed
            boolean canProceed = false;
            try {
              // Check if Next button is already ready before trying autofill
              if (requiredQuestionAutofillService.isNextButtonReady(driver)) {
                log.info("Next button is ready for section {}, proceeding to next section",
                    section.getSectionIndex());
                canProceed = true;
              } else {
                log.info("Next button not ready, attempting autofill for section {}",
                    section.getSectionIndex());
                canProceed = requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
              }
            } catch (Exception autofillEx) {
              log.warn("Autofill failed for section {}: {}", section.getSectionIndex(),
                  autofillEx.getMessage());
            }

            if (canProceed) {
              log.info("Proceeding to next section after section {}", section.getSectionIndex());
              clickNextButton(driver, wait, humanLike);
              try {
                Thread.sleep(800);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            } else {
              log.warn("Cannot proceed from section {} - Next button not ready after autofill",
                  section.getSectionIndex());
              // Try one more time with a longer wait
              try {
                Thread.sleep(2000);
                if (requiredQuestionAutofillService.isNextButtonReady(driver)) {
                  log.info("Next button ready after additional wait, proceeding");
                  clickNextButton(driver, wait, humanLike);
                  try {
                    Thread.sleep(800);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                } else {
                  log.error("Still cannot proceed from section {} after additional wait",
                      section.getSectionIndex());
                  return false;
                }
              } catch (Exception e) {
                log.error("Error during additional wait for section {}: {}",
                    section.getSectionIndex(), e.getMessage());
                return false;
              }
            }
          } else {
            // Neither Next nor Submit visible - this might be an error state
            log.warn("Neither Next nor Submit button visible after section {}",
                section.getSectionIndex());
            // Check if we're actually on the last section
            if (section.isLastSection()) {
              log.info(
                  "This is the last section according to metadata, but no Submit button found");
              // Don't fail here, let the final section handling take care of it
              log.info(
                  "Continuing to next section, final section handling will check for Submit button");
            } else {
              log.warn("No navigation buttons visible but not on last section - continuing anyway");
              // Continue processing instead of failing
            }
          }
        } catch (Exception sectionError) {
          log.error("Error processing section {}: {}", section.getSectionIndex(),
              sectionError.getMessage());
          // Continue with next section instead of stopping
          continue;
        }
      }

      // CRITICAL FIX: Handle the final "Thank you" section that may not have questions
      // This section contains the Submit button but might not be in formStructure
      log.info(
          "Completed processing all sections with questions, checking for final Submit section...");

      try {
        // Wait a bit for the final section to load
        Thread.sleep(1000);

        // Check if we're on the final section with Submit button
        if (isSubmitButtonVisible(driver)) {
          log.info("Found Submit button on final section - this is the 'Thank you' section");
          if (autoSubmitEnabled) {
            log.info("Auto-submit enabled, clicking Submit button on final section");
            clickSubmitButton(driver, wait, humanLike);
          } else {
            log.info("Auto-submit disabled, but Submit button is visible on final section");
          }
        } else {
          // Try to navigate to the final section if Next button is still available
          log.info(
              "Submit button not visible, checking if Next button is available to reach final section");

          int maxNavigationAttempts = 3;
          for (int attempt = 1; attempt <= maxNavigationAttempts; attempt++) {
            log.info("Navigation attempt {}/{} to reach final section", attempt,
                maxNavigationAttempts);

            if (isNextButtonVisible(driver)) {
              log.info("Next button visible, clicking to reach final section");
              try {
                clickNextButton(driver, wait, humanLike);
                Thread.sleep(1000);

                // Check if Submit button is now visible
                if (isSubmitButtonVisible(driver)) {
                  log.info("Successfully reached final section with Submit button");
                  if (autoSubmitEnabled) {
                    clickSubmitButton(driver, wait, humanLike);
                  }
                  break;
                } else {
                  log.info("Next button clicked but Submit button not yet visible, continuing...");
                }
              } catch (Exception navEx) {
                log.warn("Error clicking Next button on attempt {}: {}", attempt,
                    navEx.getMessage());
              }
            } else {
              log.info("No Next button visible on attempt {}, may already be on final section",
                  attempt);

              // Double-check if Submit button is visible
              if (isSubmitButtonVisible(driver)) {
                log.info("Submit button found on final section");
                if (autoSubmitEnabled) {
                  clickSubmitButton(driver, wait, humanLike);
                }
                break;
              }

              // Wait a bit more and try again
              try {
                Thread.sleep(2000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          }
        }
      } catch (Exception finalSectionEx) {
        log.warn("Error handling final section: {}", finalSectionEx.getMessage());
        // Don't fail the entire process, just log the issue
      }

      log.info("Successfully completed multi-section form fill for request: {}", fillRequestId);
      return true;

    } catch (Exception e) {
      log.error("Error filling multi-section form for request {}: {}", fillRequestId,
          e.getMessage(), e);
      return false;
    } finally {
      if (driver != null) {
        // Add additional delay before shutdown to ensure form submission is fully processed
        try {
          log.info(
              "Waiting 3 seconds before shutdown to ensure form submission is fully processed...");
          Thread.sleep(3000);
          log.info("Shutdown delay completed");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Interrupted during shutdown delay");
        }
        shutdownDriver(driver);
      }
    }
  }

  // Fill questions on the very first page (intro) that lack section_index metadata
  private int fillIntroQuestionsIfAny(WebDriver driver, Map<Question, QuestionOption> selections,
      boolean humanLike) {
    int filled = 0;
    try {
      // Collect entries without section_index and with a mapped option, sort by position (0-based)
      List<Map.Entry<Question, QuestionOption>> introEntries =
          selections.entrySet().stream().filter(e -> {
            Question q = e.getKey();
            QuestionOption opt = e.getValue();
            if (opt == null)
              return false;
            Map<String, String> add = q.getAdditionalData();
            boolean hasSectionIndex = add != null && add.get("section_index") != null;
            return !hasSectionIndex;
          }).sorted(Comparator.comparingInt(e -> {
            Integer pos = e.getKey().getPosition();
            return pos != null ? pos : Integer.MAX_VALUE;
          })).toList();

      // Snapshot listitems of current (first) page
      List<WebElement> listItems = driver.findElements(By.cssSelector("div[role='listitem']"));

      for (Map.Entry<Question, QuestionOption> e : introEntries) {
        Question q = e.getKey();
        QuestionOption opt = e.getValue();

        try {
          WebElement el = null;
          Map<String, String> add = q.getAdditionalData();

          // Priority 1: liIndex (0-based) when available
          if (add != null && add.get("liIndex") != null) {
            try {
              int li = Integer.parseInt(add.get("liIndex"));
              if (li >= 0 && li < listItems.size()) {
                el = listItems.get(li);
              }
            } catch (Exception ignore) {
            }
          }

          // Priority 2: position (0-based)
          if (el == null) {
            Integer pos = q.getPosition();
            if (pos != null && pos >= 0 && pos < listItems.size()) {
              el = listItems.get(pos);
            }
          }

          // Fallback: existing resolver
          if (el == null) {
            el = formFillingHelper.resolveQuestionElement(driver, "", q);
          }

          if (el != null) {
            boolean ok = formFillingHelper.fillQuestionByType(driver, el, q, opt, humanLike);
            if (ok)
              filled++;
          }
        } catch (Exception ignore) {
        }
      }
    } catch (Exception ex) {
      log.debug("fillIntroQuestionsIfAny error: {}", ex.getMessage());
    }
    return filled;
  }

  /**
   * Fill questions in a specific section
   */
  private int fillQuestionsInSection(WebDriver driver, SectionInfo section,
      Map<Question, QuestionOption> selections, Map<java.util.UUID, QuestionOption> optionById,
      Map<String, QuestionOption> optionByKey, boolean humanLike) {
    int filledCount = 0;

    log.info("Starting to fill questions in section {}: {} with {} questions",
        section.getSectionIndex(), section.getSectionTitle(), section.getQuestions().size());

    for (QuestionInfo questionInfo : section.getQuestions()) {
      Question question = questionInfo.getQuestionEntity();
      QuestionOption option = findOptionForQuestion(question, optionById, optionByKey);

      // Fix log message format and add detailed debugging
      log.info("Processing question: '{}' (ID: {}) with option: '{}' (ID: {})", question.getTitle(),
          question.getId(), option != null ? option.getText() : "null",
          option != null ? option.getId() : "null");

      if (option != null) {
        // Validate that the option belongs to the correct question
        if (option.getQuestion() != null
            && !option.getQuestion().getId().equals(question.getId())) {
          log.error(
              "CRITICAL: Option '{}' (ID: {}) does not belong to question '{}' (ID: {})! Option belongs to question '{}' (ID: {})",
              option.getText(), option.getId(), question.getTitle(), question.getId(),
              option.getQuestion().getTitle(), option.getQuestion().getId());
          continue; // Skip this question to avoid wrong mapping
        }

        try {
          // Use FormFillingHelper to resolve and fill question
          WebElement questionElement =
              formFillingHelper.resolveQuestionElement(driver, "", question);
          if (questionElement != null) {
            log.info("Found question element for: {}", question.getTitle());

            // Check if question is already filled to avoid duplicate filling
            if (isQuestionAlreadyFilled(driver, questionElement, question, option)) {
              log.info("Question {} is already filled, skipping", question.getTitle());
              filledCount++;
              continue;
            }

            boolean fillSuccess = formFillingHelper.fillQuestionByType(driver, questionElement,
                question, option, humanLike);

            // If this question used 'Other', fill its text now.
            try {
              if (fillSuccess && option != null) {
                boolean needsOtherFill = false;
                if (option.getValue() != null
                    && option.getValue().equalsIgnoreCase("__other_option__")) {
                  needsOtherFill = true;
                }
                // Checkbox path: if the token list contains __other_option__
                if (!needsOtherFill && "checkbox".equalsIgnoreCase(question.getType())) {
                  String text = option.getText();
                  if (text != null && text.contains("__other_option__")) {
                    needsOtherFill = true;
                  }
                }
                if (needsOtherFill) {
                  // Prefer explicit sample from option text if present (e.g.,
                  // "__other_option__-text")
                  String optText = option.getText();
                  String explicit = null;
                  if (optText != null) {
                    int di = optText.lastIndexOf('-');
                    if (di > 0) {
                      String before = optText.substring(0, di).trim();
                      String after = optText.substring(di + 1).trim();
                      if (!after.isEmpty() && ("__other_option__".equalsIgnoreCase(before)
                          || before.matches("\\d+"))) {
                        explicit = after;
                      }
                    }
                  }
                  if (explicit != null) {
                    fillOtherTextDirectly(driver, questionElement, explicit, humanLike);
                  } else {
                    fillOtherTextForQuestion(driver, questionElement, question.getId(), humanLike);
                  }
                }
              }
            } catch (Exception ignore) {
              log.debug("Failed to fill 'Other' text for question {}: {}", question.getTitle(),
                  ignore.getMessage());
            }

            if (fillSuccess) {
              filledCount++;
              log.info("Successfully filled question: {} in section {}", question.getTitle(),
                  section.getSectionIndex());
            } else {
              log.warn("Failed to fill question: {} in section {}", question.getTitle(),
                  section.getSectionIndex());
            }
          } else {
            log.warn("Could not resolve question element: {} in section {}", question.getTitle(),
                section.getSectionIndex());
          }
        } catch (Exception e) {
          log.warn("Failed to fill question {} in section {}: {}", question.getTitle(),
              section.getSectionIndex(), e.getMessage());
        }
      } else {
        log.warn("No option found for question: {} in section {}", question.getTitle(),
            section.getSectionIndex());
      }
    }

    log.info("Completed filling questions in section {}: filled {}/{} questions",
        section.getSectionIndex(), filledCount, section.getQuestions().size());
    return filledCount;
  }

  /**
   * Check if a question is already filled with the expected value
   */
  private boolean isQuestionAlreadyFilled(WebDriver driver, WebElement questionElement,
      Question question, QuestionOption option) {
    try {
      String questionType = question.getType().toLowerCase();

      switch (questionType) {
        case "radio":
          return isRadioQuestionFilled(questionElement, option.getText());
        case "checkbox":
          return isCheckboxQuestionFilled(questionElement, option.getText());
        case "text":
        case "email":
        case "textarea":
        case "short_answer":
        case "paragraph":
          return isTextQuestionFilled(questionElement, option.getText());
        case "combobox":
        case "select":
          return isComboboxQuestionFilled(questionElement, option.getText());
        case "multiple_choice_grid":
          return isMultipleChoiceGridQuestionFilled(questionElement, option);
        case "checkbox_grid":
          return isCheckboxGridQuestionFilled(questionElement, option);
        default:
          return false; // For other types, assume not filled
      }
    } catch (Exception e) {
      log.debug("Error checking if question {} is filled: {}", question.getTitle(), e.getMessage());
      return false;
    }
  }

  /**
   * Check if radio question is already filled
   */
  private boolean isRadioQuestionFilled(WebElement questionElement, String expectedValue) {
    try {
      List<WebElement> radioOptions =
          questionElement.findElements(By.cssSelector("[role='radio']"));
      for (WebElement radio : radioOptions) {
        String ariaChecked = radio.getAttribute("aria-checked");
        if ("true".equals(ariaChecked)) {
          String dataValue = radio.getAttribute("data-value");
          String ariaLabel = radio.getAttribute("aria-label");
          if ((dataValue != null && dataValue.trim().equals(expectedValue.trim()))
              || (ariaLabel != null && ariaLabel.trim().equals(expectedValue.trim()))) {
            return true;
          }
        }
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Check if checkbox question is already filled
   */
  private boolean isCheckboxQuestionFilled(WebElement questionElement, String expectedValue) {
    try {
      List<WebElement> checkboxOptions =
          questionElement.findElements(By.cssSelector("[role='checkbox']"));
      for (WebElement checkbox : checkboxOptions) {
        String ariaChecked = checkbox.getAttribute("aria-checked");
        if ("true".equals(ariaChecked)) {
          String dataValue = checkbox.getAttribute("data-value");
          String ariaLabel = checkbox.getAttribute("aria-label");
          if ((dataValue != null && dataValue.trim().equals(expectedValue.trim()))
              || (ariaLabel != null && ariaLabel.trim().equals(expectedValue.trim()))) {
            return true;
          }
        }
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Check if text question is already filled
   */
  private boolean isTextQuestionFilled(WebElement questionElement, String expectedValue) {
    try {
      WebElement input = questionElement
          .findElement(By.cssSelector("input[type='text'], input[type='email'], textarea"));
      String currentValue = input.getAttribute("value");
      return expectedValue.trim().equals(currentValue != null ? currentValue.trim() : "");
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Check if combobox question is already filled
   */
  private boolean isComboboxQuestionFilled(WebElement questionElement, String expectedValue) {
    try {
      WebElement combobox = questionElement.findElement(By.cssSelector("[role='listbox']"));
      String ariaLabel = combobox.getAttribute("aria-label");
      return expectedValue.trim().equals(ariaLabel != null ? ariaLabel.trim() : "");
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Check if multiple choice grid question is already filled
   */
  private boolean isMultipleChoiceGridQuestionFilled(WebElement questionElement,
      QuestionOption option) {
    try {
      log.debug("Checking if multiple choice grid question is already filled with option: {}",
          option.getText());

      // Find all radio groups in the grid
      List<WebElement> radioGroups =
          questionElement.findElements(By.cssSelector("[role='radiogroup']"));
      log.debug("Found {} radio groups in multiple choice grid", radioGroups.size());

      if (radioGroups.isEmpty()) {
        log.debug("No radio groups found, assuming not filled");
        return false;
      }

      // Check if any radio button in any group is already selected
      int filledRows = 0;
      for (WebElement radioGroup : radioGroups) {
        List<WebElement> radios = radioGroup.findElements(By.cssSelector("[role='radio']"));
        for (WebElement radio : radios) {
          String ariaChecked = radio.getAttribute("aria-checked");
          if ("true".equals(ariaChecked)) {
            filledRows++;
            break; // Found a selected radio in this row, move to next row
          }
        }
      }

      log.debug("Found {} filled rows out of {} total rows in multiple choice grid", filledRows,
          radioGroups.size());

      // Consider the grid filled if at least one row has a selection
      // This is a conservative approach to avoid re-filling partially completed grids
      return filledRows > 0;
    } catch (Exception e) {
      log.debug("Error checking multiple choice grid question: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Check if checkbox grid question is already filled
   */
  private boolean isCheckboxGridQuestionFilled(WebElement questionElement, QuestionOption option) {
    try {
      log.debug("Checking if checkbox grid question is already filled with option: {}",
          option.getText());

      // Find all checkbox groups in the grid - use more specific selector for checkbox grids
      List<WebElement> checkboxGroups =
          questionElement.findElements(By.cssSelector("[role='group']:not([aria-hidden='true'])"));

      // Filter to only include groups that contain checkboxes
      checkboxGroups = checkboxGroups.stream()
          .filter(group -> !group.findElements(By.cssSelector("[role='checkbox']")).isEmpty())
          .collect(java.util.stream.Collectors.toList());

      log.debug("Found {} checkbox groups in checkbox grid", checkboxGroups.size());

      if (checkboxGroups.isEmpty()) {
        log.debug("No checkbox groups found, assuming not filled");
        return false;
      }

      // Check if any checkbox in any group is already selected
      int filledRows = 0;
      for (WebElement checkboxGroup : checkboxGroups) {
        List<WebElement> checkboxes =
            checkboxGroup.findElements(By.cssSelector("[role='checkbox']"));
        for (WebElement checkbox : checkboxes) {
          String ariaChecked = checkbox.getAttribute("aria-checked");
          if ("true".equals(ariaChecked)) {
            filledRows++;
            break; // Found a selected checkbox in this row, move to next row
          }
        }
      }

      log.debug("Found {} filled rows out of {} total rows in checkbox grid", filledRows,
          checkboxGroups.size());

      // Consider the grid filled if at least one row has a selection
      // This is a conservative approach to avoid re-filling partially completed grids
      return filledRows > 0;
    } catch (Exception e) {
      log.debug("Error checking checkbox grid question: {}", e.getMessage());
      return false;
    }
  }

  // ===== Helpers: stable option lookup across sections =====
  private Map<java.util.UUID, QuestionOption> buildOptionByQuestionId(
      Map<Question, QuestionOption> selections) {
    java.util.Map<java.util.UUID, QuestionOption> map = new java.util.HashMap<>();

    for (Map.Entry<Question, QuestionOption> e : selections.entrySet()) {
      try {
        if (e.getKey() != null && e.getKey().getId() != null && e.getValue() != null) {
          map.put(e.getKey().getId(), e.getValue());
        }
      } catch (Exception ignore) {
      }
    }

    return map;
  }

  private Map<String, QuestionOption> buildOptionByCompositeKey(
      Map<Question, QuestionOption> selections, Map<String, String> columnMappings) {
    java.util.Map<String, QuestionOption> map = new java.util.HashMap<>();

    for (Map.Entry<Question, QuestionOption> e : selections.entrySet()) {
      try {
        Question q = e.getKey();
        QuestionOption o = e.getValue();
        if (q == null || o == null)
          continue;
        String key = buildCompositeKey(q, columnMappings);
        if (key != null) {
          map.put(key, o);
        }
      } catch (Exception ignore) {
      }
    }

    return map;
  }

  private QuestionOption findOptionForQuestion(Question question,
      Map<java.util.UUID, QuestionOption> optionById, Map<String, QuestionOption> optionByKey) {
    try {
      if (question == null)
        return null;

      if (question.getId() != null && optionById != null) {
        QuestionOption byId = optionById.get(question.getId());
        if (byId != null) {
          return byId;
        }
      }

      if (optionByKey != null) {
        // Lấy column mappings từ currentFillRequestId để tạo key chính xác
        Map<String, String> columnMappings = getColumnMappingsFromFillRequest(currentFillRequestId);
        String key = buildCompositeKey(question, columnMappings);
        if (key != null) {
          QuestionOption byKey = optionByKey.get(key);
          if (byKey != null) {
            return byKey;
          }
        }
      }

      // Fallback: Try to find option by question title when no column mappings available
      if (optionByKey != null) {
        log.debug(
            "No column mappings available, trying fallback with question title for question: {}",
            question.getTitle());
        String fallbackKey = buildCompositeKey(question, null); // Use null columnMappings to
                                                                // fallback to title
        if (fallbackKey != null) {
          QuestionOption byFallbackKey = optionByKey.get(fallbackKey);
          if (byFallbackKey != null) {
            log.debug("Found option using fallback key '{}' for question: {}", fallbackKey,
                question.getTitle());
            return byFallbackKey;
          }
        }
      }

      // Final fallback: Try to find option from answer distributions if available
      if (currentFillRequestId != null) {
        try {
          QuestionOption fromDistribution = findOptionFromAnswerDistribution(question);
          if (fromDistribution != null) {
            log.debug("Found option from answer distribution for question: {}",
                question.getTitle());
            return fromDistribution;
          }
        } catch (Exception e) {
          log.debug("Error finding option from answer distribution for question '{}': {}",
              question.getTitle(), e.getMessage());
        }
      }

      return null;
    } catch (Exception e) {
      log.error("Error finding option for question '{}': {}", question.getTitle(), e.getMessage(),
          e);
    }
    return null;
  }

  private String buildCompositeKey(Question q, Map<String, String> columnMappings) {
    try {
      StringBuilder sb = new StringBuilder();
      String formPart =
          q.getForm() != null && q.getForm().getId() != null ? q.getForm().getId().toString() : "";
      String typePart = q.getType() != null ? q.getType().trim().toLowerCase() : "";
      String sectionPart = null;
      Map<String, String> add = q.getAdditionalData();
      if (add != null) {
        sectionPart = add.get("section_index");
      }
      if (sectionPart == null)
        sectionPart = "";

      // Ưu tiên sử dụng column name từ mappings, nếu không có thì dùng question title
      String titlePart;
      if (columnMappings != null && q.getId() != null) {
        String columnName = columnMappings.get(q.getId().toString());
        if (columnName != null && !columnName.trim().isEmpty()) {
          titlePart = columnName.trim();
          log.debug("Using column name '{}' for question '{}' (ID: {})", columnName, q.getTitle(),
              q.getId());
        } else {
          titlePart = q.getTitle() != null ? q.getTitle().trim() : "";
          log.debug("No column mapping found, using question title '{}' for question ID: {}",
              titlePart, q.getId());
        }
      } else {
        titlePart = q.getTitle() != null ? q.getTitle().trim() : "";
        log.debug("No column mappings available, using question title '{}' for question ID: {}",
            titlePart, q.getId());
      }

      sb.append(formPart).append("|").append(sectionPart).append("|").append(typePart).append("|")
          .append(titlePart);
      return sb.toString();
    } catch (Exception e) {
      log.error("Error building composite key for question '{}': {}", q.getTitle(), e.getMessage());
      return null;
    }
  }

  /**
   * Click Next button to move to next section with improved retry logic
   */
  private void clickNextButton(WebDriver driver, WebDriverWait wait, boolean humanLike) {
    int maxRetries = 3;
    int retryCount = 0;

    while (retryCount < maxRetries) {
      try {
        log.info("Looking for Next button (attempt {}/{})...", retryCount + 1, maxRetries);

        // If Submit button is already visible, do not try Next
        try {
          List<WebElement> submitCandidates = driver.findElements(SUBMIT_BUTTON);
          for (WebElement el : submitCandidates) {
            if (el.isDisplayed()) {
              log.info("Submit button is visible; skipping Next");
              return;
            }
          }
        } catch (Exception ignore) {
        }

        // Try multiple selectors for Next button
        WebElement nextButton = null;
        // Strategy 1: Primary selectors
        By[] primarySelectors =
            {NEXT_BUTTON, By.xpath("//div[@role='button' and contains(., 'Tiếp')]"),
                By.xpath("//div[@role='button' and contains(., 'Next')]"),
                By.xpath("//span[contains(text(), 'Tiếp')]/ancestor::div[@role='button']"),
                By.xpath("//span[contains(text(), 'Next')]/ancestor::div[@role='button']")};

        for (By selector : primarySelectors) {
          try {
            log.debug("Trying Next button selector: {}", selector);
            nextButton = wait.until(ExpectedConditions.elementToBeClickable(selector));
            log.info("Found Next button using selector: {}", selector);
            break;
          } catch (Exception e) {
            log.debug("Selector {} failed: {}", selector, e.getMessage());
          }
        }

        // Strategy 2: Try to find any button that might be Next
        if (nextButton == null) {
          log.info("Primary selectors failed, trying to find any navigation button...");
          try {
            List<WebElement> allButtons = driver.findElements(By.cssSelector("[role='button']"));
            log.info("Found {} buttons on page", allButtons.size());

            for (WebElement button : allButtons) {
              try {
                String buttonText = button.getText().toLowerCase();
                String ariaLabel = button.getAttribute("aria-label");
                if (ariaLabel != null) {
                  ariaLabel = ariaLabel.toLowerCase();
                }

                log.debug("Button text: '{}', aria-label: '{}'", buttonText, ariaLabel);

                if ((buttonText.contains("tiếp") || buttonText.contains("next"))
                    || (ariaLabel != null
                        && (ariaLabel.contains("tiếp") || ariaLabel.contains("next")))) {
                  nextButton = button;
                  log.info("Found Next button by text/aria-label: {}", buttonText);
                  break;
                }
              } catch (Exception e) {
                log.debug("Error checking button: {}", e.getMessage());
              }
            }
          } catch (Exception e) {
            log.debug("Error finding all buttons: {}", e.getMessage());
          }
        }

        if (nextButton == null) {
          log.error("Could not find Next button with any selector (attempt {}/{})", retryCount + 1,
              maxRetries);
          retryCount++;
          if (retryCount < maxRetries) {
            try {
              Thread.sleep(1000 * retryCount); // Exponential backoff
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            continue;
          } else {
            throw new RuntimeException(
                "Could not find Next button after " + maxRetries + " attempts");
          }
        }

        // Verify button is enabled before clicking
        try {
          String ariaDisabled = nextButton.getAttribute("aria-disabled");
          if ("true".equals(ariaDisabled)) {
            log.warn("Next button is disabled (attempt {}/{}), waiting for it to become enabled",
                retryCount + 1, maxRetries);
            retryCount++;
            if (retryCount < maxRetries) {
              try {
                Thread.sleep(1000 * retryCount);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              continue;
            } else {
              throw new RuntimeException(
                  "Next button remained disabled after " + maxRetries + " attempts");
            }
          }
        } catch (Exception e) {
          log.debug("Could not check aria-disabled attribute: {}", e.getMessage());
        }

        if (humanLike) {
          try {
            Thread.sleep(250 + new Random().nextInt(251));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting", e);
          }
        }

        nextButton.click();
        log.info("Clicked Next button (attempt {}/{})", retryCount + 1, maxRetries);

        // Wait for section change - try multiple approaches
        try {
          wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
          log.info("Form element found after clicking Next");
        } catch (Exception e) {
          log.warn("Could not detect form element change, continuing anyway");
        }

        log.info("Successfully clicked Next button and moved to next section");
        return; // Success, exit retry loop

      } catch (Exception e) {
        retryCount++;
        log.warn("Error clicking Next button (attempt {}/{}): {}", retryCount, maxRetries,
            e.getMessage());

        if (retryCount < maxRetries) {
          try {
            Thread.sleep(1000 * retryCount); // Exponential backoff
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
        } else {
          log.error("Failed to click Next button after {} attempts", maxRetries);
          throw e;
        }
      }
    }
  }

  /**
   * Click Submit button to submit the form with improved retry logic
   */
  private void clickSubmitButton(WebDriver driver, WebDriverWait wait, boolean humanLike) {
    int maxRetries = 3;
    int retryCount = 0;

    while (retryCount < maxRetries) {
      try {
        log.info("Looking for Submit button (attempt {}/{})...", retryCount + 1, maxRetries);

        // Try multiple selectors for Submit button
        WebElement submitButton = null;
        try {
          By[] alternativeSelectors =
              {SUBMIT_BUTTON, By.xpath("//div[@role='button' and @aria-label='Submit']"),
                  By.xpath("//div[@role='button' and @aria-label='Gửi']"),
                  By.xpath("//div[@role='button' and .//span[contains(text(), 'Gửi')]]"),
                  By.xpath("//div[@role='button' and .//span[contains(text(), 'Submit')]]"),
                  By.xpath("//div[@role='button' and contains(., 'Submit')]"),
                  By.xpath("//div[@role='button' and contains(., 'Gửi')]")};

          for (By selector : alternativeSelectors) {
            try {
              log.debug("Trying Submit button selector: {}", selector);
              submitButton = wait.until(ExpectedConditions.elementToBeClickable(selector));
              log.info("Found Submit button using selector: {}", selector);
              break;
            } catch (Exception selectorEx) {
              log.debug("Selector {} failed: {}", selector, selectorEx.getMessage());
            }
          }
        } catch (Exception e) {
          log.error("Error finding Submit button: {}", e.getMessage());
        }

        // Strategy 2: Try to find any button that might be Submit
        if (submitButton == null) {
          log.info("Primary selectors failed, trying to find any submit button...");
          try {
            List<WebElement> allButtons = driver.findElements(By.cssSelector("[role='button']"));
            log.info("Found {} buttons on page", allButtons.size());

            for (WebElement button : allButtons) {
              try {
                String buttonText = button.getText().toLowerCase();
                String ariaLabel = button.getAttribute("aria-label");
                if (ariaLabel != null) {
                  ariaLabel = ariaLabel.toLowerCase();
                }

                log.debug("Button text: '{}', aria-label: '{}'", buttonText, ariaLabel);

                if ((buttonText.contains("submit") || buttonText.contains("gửi"))
                    || (ariaLabel != null
                        && (ariaLabel.contains("submit") || ariaLabel.contains("gửi")))) {
                  submitButton = button;
                  log.info("Found Submit button by text/aria-label: {}", buttonText);
                  break;
                }
              } catch (Exception e) {
                log.debug("Error checking button: {}", e.getMessage());
              }
            }
          } catch (Exception e) {
            log.debug("Error finding all buttons: {}", e.getMessage());
          }
        }

        if (submitButton == null) {
          log.error("Could not find Submit button with any selector (attempt {}/{})",
              retryCount + 1, maxRetries);
          retryCount++;
          if (retryCount < maxRetries) {
            try {
              Thread.sleep(1000 * retryCount); // Exponential backoff
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            continue;
          } else {
            throw new RuntimeException(
                "Could not find Submit button after " + maxRetries + " attempts");
          }
        }

        // Verify button is enabled before clicking
        try {
          String ariaDisabled = submitButton.getAttribute("aria-disabled");
          if ("true".equals(ariaDisabled)) {
            log.warn("Submit button is disabled (attempt {}/{}), waiting for it to become enabled",
                retryCount + 1, maxRetries);
            retryCount++;
            if (retryCount < maxRetries) {
              try {
                Thread.sleep(1000 * retryCount);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              continue;
            } else {
              throw new RuntimeException(
                  "Submit button remained disabled after " + maxRetries + " attempts");
            }
          }
        } catch (Exception e) {
          log.debug("Could not check aria-disabled attribute: {}", e.getMessage());
        }

        if (humanLike) {
          try {
            Thread.sleep(250 + new Random().nextInt(251));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting", e);
          }
        }

        submitButton.click();
        log.info("Clicked Submit button (attempt {}/{})", retryCount + 1, maxRetries);

        // Wait for submission confirmation with multiple approaches and longer timeout
        boolean submitConfirmed = false;
        try {
          // Wait for URL change to formResponse (primary indicator)
          WebDriverWait submitWait = new WebDriverWait(driver, Duration.ofSeconds(20));
          submitWait.until(ExpectedConditions.urlContains("formResponse"));
          log.info("Form submitted successfully - URL contains 'formResponse'");
          submitConfirmed = true;
        } catch (Exception e) {
          log.warn("Could not detect form submission via URL change: {}", e.getMessage());

          // Fallback: Wait for submit button to disappear or become disabled
          try {
            WebDriverWait fallbackWait = new WebDriverWait(driver, Duration.ofSeconds(15));
            fallbackWait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.xpath("//div[@role='button' and @aria-label='Submit']")));
            log.info("Form submitted successfully - Submit button disappeared");
            submitConfirmed = true;
          } catch (Exception fallbackEx) {
            log.warn("Could not detect submit button disappearance: {}", fallbackEx.getMessage());
          }
        }

        // Additional wait to ensure submission is fully processed
        if (submitConfirmed) {
          try {
            log.info("Waiting additional 2 seconds to ensure submission is fully processed...");
            Thread.sleep(2000);
            log.info("Additional wait completed");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during additional wait");
          }
        } else {
          log.warn("Submit confirmation not detected, but continuing anyway");
        }

        log.info("Submit process completed - confirmed: {}", submitConfirmed);

        // Additional verification: check if we can detect successful submission
        if (submitConfirmed) {
          verifySubmissionSuccess(driver);
        }

        return; // Success, exit retry loop

      } catch (Exception e) {
        retryCount++;
        log.warn("Error clicking Submit button (attempt {}/{}): {}", retryCount, maxRetries,
            e.getMessage());

        if (retryCount < maxRetries) {
          try {
            Thread.sleep(1000 * retryCount); // Exponential backoff
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
        } else {
          log.error("Failed to click Submit button after {} attempts", maxRetries);
          throw e;
        }
      }
    }
  }

  /**
   * Open browser with optimized settings
   */
  private WebDriver openBrowser(String formUrl, boolean humanLike) {
    log.info("Opening browser for URL: {}", formUrl);

    ChromeOptions options = new ChromeOptions();
    java.nio.file.Path userDataDir = null;

    // Essential Chrome options for stability and performance
    options.addArguments("--remote-allow-origins=*");
    options.addArguments("--no-sandbox");
    options.addArguments("--disable-dev-shm-usage");

    // Set window size for consistent behavior between headless and non-headless
    options.addArguments("--window-size=1920,1080");
    options.addArguments("--start-maximized");

    // Performance optimizations
    options.addArguments("--disable-gpu");
    options.addArguments("--disable-extensions");
    options.addArguments("--disable-plugins");
    options.addArguments("--disable-images");
    options.addArguments("--disable-web-security");
    options.addArguments("--disable-features=VizDisplayCompositor");
    options.addArguments("--disable-blink-features=AutomationControlled");
    options.addArguments("--disable-infobars");
    options.addArguments("--disable-notifications");
    options.addArguments("--disable-popup-blocking");
    options.addArguments("--disable-save-password-bubble");
    options.addArguments("--disable-translate");
    options.addArguments("--no-first-run");
    options.addArguments("--no-default-browser-check");

    // Add viewport and device emulation for consistent rendering
    if (headless) {
      options.addArguments("--headless=new");
      // Additional headless-specific optimizations
      options.addArguments("--force-device-scale-factor=1");
      options.addArguments("--high-dpi-support=1");
      options.addArguments("--force-color-profile=srgb");
      log.info("Running Chrome in headless mode with window size 1920x1080");
    } else {
      log.info("Running Chrome in non-headless mode with window size 1920x1080");
    }

    if (humanLike) {
      // Add user agent for more human-like behavior
      options.addArguments(
          "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
    }

    // Faster page initialization
    options.setPageLoadStrategy(org.openqa.selenium.PageLoadStrategy.EAGER);

    // Disable images to speed up load
    java.util.Map<String, Object> prefs = new java.util.HashMap<>();
    prefs.put("profile.managed_default_content_settings.images", 2);
    options.setExperimentalOption("prefs", prefs);

    // Disable automation flags to prevent detection
    options.setExperimentalOption("excludeSwitches",
        java.util.Collections.singletonList("enable-automation"));
    options.setExperimentalOption("useAutomationExtension", false);

    // Isolated Chrome profile per session
    try {
      userDataDir = java.nio.file.Files.createTempDirectory("df-chrome-");
      options.addArguments("--user-data-dir=" + userDataDir.toAbsolutePath());
      options.addArguments("--profile-directory=Default");
      options.addArguments("--incognito");
      options.addArguments("--disable-background-networking");
      options.addArguments("--dns-prefetch-disable");
      options.addArguments("--aggressive-cache-discard");
      options.addArguments("--disable-cache");
      options.addArguments("--disable-application-cache");
      log.debug("Created isolated Chrome user-data-dir at {}", userDataDir);
    } catch (Exception e) {
      log.warn("Failed to create isolated Chrome profile directory: {}", e.getMessage());
    }

    WebDriver driver = new ChromeDriver(options);
    if (userDataDir != null) {
      driverProfileDirMap.put(driver, userDataDir);
    }

    // Set viewport size after driver creation for headless mode
    if (headless) {
      try {
        // Set viewport size to ensure consistent rendering
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1920, 1080));
        Thread.sleep(500);
      } catch (Exception e) {
        log.warn("Failed to set viewport size: {}", e.getMessage());
      }
    }

    try {
      log.info("Navigating to form URL...");
      driver.get(formUrl);

      // Wait for page to fully load and stabilize in headless mode
      if (headless) {
        try {
          Thread.sleep(2000); // Give extra time for layout to settle

          // Ensure viewport is properly set after page load
          driver.manage().window().setSize(new org.openqa.selenium.Dimension(1920, 1080));
        } catch (Exception e) {
          log.warn("Error during headless mode stabilization: {}", e.getMessage());
        }
      }

      // Wait for page to load
      log.info("Waiting for page to load...");
      try {
        Thread.sleep(3000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while waiting for page load", e);
      }

      // Check if page loaded successfully
      String currentUrl = driver.getCurrentUrl();
      log.info("Current URL after navigation: {}", currentUrl);

      // Wait for form to be present with a retry; fail fast if not found to avoid long timeouts
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
      boolean formPresent = false;
      try {
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
        formPresent = true;
        log.info("Form element found on page");
      } catch (Exception e) {
        log.warn("Form element not found after first wait: {}. Retrying once...", e.getMessage());
        try {
          // Simple retry: small pause then try again (also helps if dynamic content loads slower)
          Thread.sleep(2000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
        try {
          wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
          formPresent = true;
          log.info("Form element found on retry");
        } catch (Exception e2) {
          log.error("Form element still not found after retry: {}", e2.getMessage());
        }
      }

      if (!formPresent) {
        // Additional heuristic: detect captcha/blocked page indicators
        try {
          String pageText = driver.findElement(By.tagName("body")).getText().toLowerCase();
          if (pageText.contains("captcha") || pageText.contains("tạm thời")
              || pageText.contains("too many") || pageText.contains("quota")) {
            log.error("Detected potential rate limit/captcha page - aborting this run early");
          }
        } catch (Exception ignore) {
        }
        try {
          driver.quit();
        } catch (Exception ignore) {
        }
        throw new RuntimeException("Form did not load correctly - aborting to avoid 300s timeout");
      }

      log.info("Browser opened successfully");
      return driver;
    } catch (Exception e) {
      log.error("Error opening browser: {}", e.getMessage());
      if (driver != null) {
        driver.quit();
      }
      throw e;
    }
  }

  /**
   * Verify that form submission was successful by checking multiple indicators
   */
  private void verifySubmissionSuccess(WebDriver driver) {
    try {
      // Check if URL contains formResponse
      String currentUrl = driver.getCurrentUrl();
      if (currentUrl.contains("formResponse")) {
        log.info("Submission verification: URL contains 'formResponse' - {}", currentUrl);
      }

      // Check for success message or confirmation
      try {
        List<WebElement> successElements = driver.findElements(By.xpath(
            "//*[contains(text(), 'Thank you') or contains(text(), 'Cảm ơn') or contains(text(), 'submitted') or contains(text(), 'đã gửi')]"));
        if (!successElements.isEmpty()) {
          log.info("Submission verification: Found success message - {}", successElements.get(0)
              .getText().substring(0, Math.min(100, successElements.get(0).getText().length())));
        }
      } catch (Exception e) {
        log.debug("Could not find success message: {}", e.getMessage());
      }

      // Check if submit button is no longer visible
      try {
        List<WebElement> submitButtons =
            driver.findElements(By.xpath("//div[@role='button' and @aria-label='Submit']"));
        if (submitButtons.isEmpty()) {
          log.info("Submission verification: Submit button no longer visible");
        } else {
          log.warn("Submission verification: Submit button still visible");
        }
      } catch (Exception e) {
        log.debug("Could not check submit button visibility: {}", e.getMessage());
      }

    } catch (Exception e) {
      log.warn("Error during submission verification: {}", e.getMessage());
    }
  }

  /**
   * Shutdown WebDriver safely
   */
  private void shutdownDriver(WebDriver driver) {
    if (driver == null)
      return;
    try {
      try {
        driver.quit();
      } catch (Exception ignore) {
      }
      // Cleanup isolated profile dir
      try {
        Path profile = driverProfileDirMap.remove(driver);
        if (profile != null) {
          Files.walk(profile).sorted(Comparator.reverseOrder()).forEach(p -> {
            try {
              Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
          });
          log.debug("Removed isolated Chrome profile at {}", profile);
        }
      } catch (Exception cleanupEx) {
        log.debug("Failed cleaning Chrome profile dir: {}", cleanupEx.getMessage());
      }
    } catch (Exception e) {
      log.warn("Unexpected error during driver shutdown: {}", e.getMessage());
    }
  }

  /**
   * Fill the 'Other' text input using user-provided value for the specific question.
   */
  private void fillOtherTextForQuestion(WebDriver driver, WebElement questionElement,
      UUID questionId, boolean humanLike) {
    try {
      WebElement input = findOtherTextInput(questionElement);
      if (input == null)
        return;

      // If already filled, don't overwrite
      try {
        String existing = input.getAttribute("value");
        if (existing != null && !existing.trim().isEmpty()) {
          return;
        }
      } catch (Exception ignored) {
      }

      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
      wait.until(ExpectedConditions.elementToBeClickable(input));

      input.click();
      try {
        input.clear();
      } catch (Exception ignored) {
      }

      String sampleText = getOtherTextForPosition(questionId);

      if (sampleText == null) {
        // Debug: Let's check what's happening
        debugValueStringLookup(questionId);
        sampleText = generateAutoOtherText();
      }

      if (humanLike) {
        for (char c : sampleText.toCharArray()) {
          input.sendKeys(String.valueOf(c));
          try {
            Thread.sleep(40 + new java.util.Random().nextInt(60));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      } else {
        input.sendKeys(sampleText);
      }
      log.info("Filled 'Other' text input for question {} with value: {}", questionId, sampleText);
    } catch (Exception e) {
      log.debug("Failed to fill 'Other' input for question {}: {}", questionId, e.getMessage());
    }
  }

  /**
   * Debug method to help understand why valueString lookup is failing
   */
  private void debugValueStringLookup(UUID questionId) {
    log.warn("=== DEBUG: valueString lookup failed for questionId: {} ===", questionId);

    // Check ThreadLocal
    try {
      Map<UUID, String> local = dataFillOtherTextByQuestion.get();
      log.warn("ThreadLocal content: {}", local);
    } catch (Exception e) {
      log.warn("ThreadLocal exception: {}", e.getMessage());
    }

    // Check currentFillRequestId
    log.warn("currentFillRequestId: {}", currentFillRequestId);

    // Check AnswerDistribution directly
    if (currentFillRequestId != null) {
      try {
        FillRequest fillRequest =
            fillRequestRepository.findByIdWithAllData(currentFillRequestId).orElse(null);
        if (fillRequest != null) {
          log.warn("FillRequest found with {} AnswerDistributions",
              fillRequest.getAnswerDistributions() != null
                  ? fillRequest.getAnswerDistributions().size()
                  : 0);

          if (fillRequest.getAnswerDistributions() != null) {
            for (AnswerDistribution dist : fillRequest.getAnswerDistributions()) {
              log.warn("AnswerDistribution - questionId: {}, optionValue: {}, valueString: {}",
                  dist.getQuestion() != null ? dist.getQuestion().getId() : "null",
                  dist.getOption() != null ? dist.getOption().getValue() : "null",
                  dist.getValueString());
            }
          }
        } else {
          log.warn("FillRequest not found for ID: {}", currentFillRequestId);
        }
      } catch (Exception e) {
        log.warn("Exception checking AnswerDistribution: {}", e.getMessage());
      }
    }

    log.warn("=== END DEBUG ===");
  }

  /**
   * Find the 'Other' text input element within a question
   */
  private WebElement findOtherTextInput(WebElement questionElement) {
    try {
      // Strategy 1: Look for input with specific aria-label
      List<WebElement> exact = questionElement
          .findElements(By.cssSelector("input[type='text'][aria-label='Câu trả lời khác']"));
      for (WebElement e : exact) {
        if (e.isDisplayed() && e.isEnabled()) {
          return e;
        }
      }

      // Strategy 2: Look for any input with aria-label containing "khác"
      List<WebElement> anyAria =
          questionElement.findElements(By.cssSelector("input[type='text'][aria-label]"));
      for (WebElement e : anyAria) {
        try {
          String aria = e.getAttribute("aria-label");
          if (aria != null && aria.toLowerCase().contains("khác") && e.isDisplayed()
              && e.isEnabled()) {
            return e;
          }
        } catch (Exception ignore) {
        }
      }

      // Strategy 3: Look for input following "Mục khác:" text
      try {
        WebElement span = questionElement
            .findElement(By.xpath(".//span[@dir='auto' and normalize-space(.)='Mục khác:']"));
        WebElement container = span.findElement(By.xpath("ancestor::label/following-sibling::div"));
        return container.findElement(By.xpath(".//input[@type='text']"));
      } catch (Exception ignore) {
      }

      // Strategy 4: Look for any text input that might be the "other" input
      List<WebElement> textInputs =
          questionElement.findElements(By.cssSelector("input[type='text']"));
      for (WebElement input : textInputs) {
        if (input.isDisplayed() && input.isEnabled()) {
          // Check if this input is near "other" related text
          try {
            String parentText =
                input.findElement(By.xpath("ancestor::div")).getText().toLowerCase();
            if (parentText.contains("khác") || parentText.contains("other")) {
              return input;
            }
          } catch (Exception ignore) {
          }
        }
      }

    } catch (Exception ignored) {
    }
    return null;
  }

  /**
   * Get other text for a specific question using the same logic as GoogleFormServiceImpl
   */
  private String getOtherTextForPosition(UUID questionId) {
    String sampleText = null;

    // Prefer per-submission explicit 'Other' text provided via ThreadLocal
    try {
      Map<UUID, String> local = dataFillOtherTextByQuestion.get();
      if (local != null && questionId != null) {
        String v = local.get(questionId);
        if (v != null && !v.trim().isEmpty()) {
          sampleText = v.trim();
          return sampleText;
        }
      }
    } catch (Exception ignore) {
    }

    // Fallback: Try to get valueString from AnswerDistribution directly
    try {
      // Use the stored fill request ID
      if (currentFillRequestId != null) {
        FillRequest fillRequest =
            fillRequestRepository.findByIdWithAllData(currentFillRequestId).orElse(null);
        if (fillRequest != null && fillRequest.getAnswerDistributions() != null) {
          for (AnswerDistribution dist : fillRequest.getAnswerDistributions()) {

            if (dist.getQuestion() != null && questionId.equals(dist.getQuestion().getId())
                && dist.getOption() != null
                && "__other_option__".equalsIgnoreCase(dist.getOption().getValue())
                && dist.getValueString() != null && !dist.getValueString().trim().isEmpty()) {
              sampleText = dist.getValueString().trim();
              break;
            }
          }
        }
      }
    } catch (Exception e) {
      log.debug("Failed to get valueString from AnswerDistribution for question {}: {}", questionId,
          e.getMessage());
    }

    return sampleText;
  }

  /**
   * Fill 'Other' text input directly with provided sample text
   */
  private void fillOtherTextDirectly(WebDriver driver, WebElement questionElement, String text,
      boolean humanLike) {
    try {
      WebElement input = findOtherTextInput(questionElement);
      if (input == null) {
        log.warn("Could not find 'other' text input for direct fill");
        return;
      }

      // If already filled, don't overwrite
      try {
        String existing = input.getAttribute("value");
        if (existing != null && !existing.trim().isEmpty()) {
          return;
        }
      } catch (Exception ignored) {
      }

      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
      wait.until(ExpectedConditions.elementToBeClickable(input));

      input.click();
      try {
        input.clear();
      } catch (Exception ignored) {
      }

      if (humanLike) {
        for (char c : text.toCharArray()) {
          input.sendKeys(String.valueOf(c));
          try {
            Thread.sleep(40 + new java.util.Random().nextInt(60));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      } else {
        input.sendKeys(text);
      }
      log.info("Filled 'Other' text input directly with value: {}", text);
    } catch (Exception e) {
      log.debug("Failed to fill 'Other' input directly: {}", e.getMessage());
    }
  }

  /**
   * Generate auto text for other option
   */
  private String generateAutoOtherText() {
    String uuid = java.util.UUID.randomUUID().toString().replace("-", "");
    String prefix = uuid.length() >= 8 ? uuid.substring(0, 8) : uuid;
    return "autogen_" + prefix;
  }

  /**
   * Check if Submit button is visible on the current page.
   */
  private boolean isSubmitButtonVisible(WebDriver driver) {
    try {
      List<WebElement> submitButtons = driver.findElements(SUBMIT_BUTTON);
      for (WebElement button : submitButtons) {
        if (button.isDisplayed()) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      log.debug("Error checking Submit button visibility: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Check if Next button is visible on the current page.
   */
  private boolean isNextButtonVisible(WebDriver driver) {
    try {
      List<WebElement> nextButtons = driver.findElements(NEXT_BUTTON);
      for (WebElement button : nextButtons) {
        if (button.isDisplayed()) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      log.debug("Error checking Next button visibility: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Check if the current section has any questions
   */
  private boolean hasQuestionsInCurrentSection(WebDriver driver) {
    try {
      // Check if the current page contains any question elements (div[role='listitem'])
      List<WebElement> questionElements =
          driver.findElements(By.cssSelector("div[role='listitem']"));

      // Also check for other question indicators
      List<WebElement> textInputs =
          driver.findElements(By.cssSelector("input[type='text'], input[type='email'], textarea"));
      List<WebElement> radioGroups = driver.findElements(By.cssSelector("[role='radiogroup']"));
      List<WebElement> checkboxes = driver.findElements(By.cssSelector("[role='checkbox']"));
      List<WebElement> listboxes = driver.findElements(By.cssSelector("[role='listbox']"));

      boolean hasQuestionElements = !questionElements.isEmpty();
      boolean hasInputElements = !textInputs.isEmpty() || !radioGroups.isEmpty()
          || !checkboxes.isEmpty() || !listboxes.isEmpty();

      boolean hasQuestions = hasQuestionElements || hasInputElements;

      return hasQuestions;
    } catch (Exception e) {
      log.warn("Error checking for questions in current section: {}", e.getMessage());
      return false; // Assume no questions if an error occurs
    }
  }

  /**
   * Find option from answer distributions for a question
   */
  private QuestionOption findOptionFromAnswerDistribution(Question question) {
    try {
      if (currentFillRequestId == null) {
        return null;
      }

      FillRequest fillRequest =
          fillRequestRepository.findByIdWithAllData(currentFillRequestId).orElse(null);
      if (fillRequest == null || fillRequest.getAnswerDistributions() == null) {
        return null;
      }

      // Find answer distribution for this question
      for (AnswerDistribution dist : fillRequest.getAnswerDistributions()) {
        if (dist.getQuestion() != null && dist.getQuestion().getId().equals(question.getId())) {
          if (dist.getOption() != null) {
            return dist.getOption();
          }
        }
      }
    } catch (Exception e) {
      log.debug("Error finding option from answer distribution for question '{}': {}",
          question.getTitle(), e.getMessage());
    }
    return null;
  }

  /**
   * Get column mappings from the current fill request.
   */
  private Map<String, String> getColumnMappingsFromFillRequest(UUID fillRequestId) {
    if (fillRequestId == null) {
      return null;
    }
    try {
      // Get column mappings from FillRequestMapping repository
      List<com.dienform.tool.dienformtudong.fillrequest.entity.FillRequestMapping> mappings =
          fillRequestMappingRepository.findByFillRequestId(fillRequestId);

      if (mappings != null && !mappings.isEmpty()) {
        Map<String, String> columnMappings = new HashMap<>();
        for (com.dienform.tool.dienformtudong.fillrequest.entity.FillRequestMapping mapping : mappings) {
          columnMappings.put(mapping.getQuestionId().toString(), mapping.getColumnName());
        }
        return columnMappings;
      }
    } catch (Exception e) {
      log.warn("Failed to get column mappings for fill request {}: {}", fillRequestId,
          e.getMessage());
    }
    return null;
  }

}
