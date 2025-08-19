package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
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
      "//div[@role='button' and (.//span[normalize-space()='Gửi'] or .//span[normalize-space()='Submit'])]");
  private final FormStructureAnalyzer formStructureAnalyzer;

  private final FormRepository formRepository;
  private final FormFillingHelper formFillingHelper;
  private final RequiredQuestionAutofillService requiredQuestionAutofillService;

  @Value("${google.form.timeout-seconds:30}")
  private int timeoutSeconds;

  @Value("${google.form.headless:true}")
  private boolean headless;

  @Value("${google.form.auto-submit:true}")
  private boolean autoSubmitEnabled;

  @Override
  public boolean fillFormWithSections(UUID fillRequestId, UUID formId, String formUrl,
      Map<Question, QuestionOption> selections, boolean humanLike) {

    log.info("Starting section-aware form fill for request: {} with {} questions", fillRequestId,
        selections.size());

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
          QuestionOption opt = selections.get(q);
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
      }

      // 3. Fill each section
      for (SectionInfo section : formStructure.getSections()) {
        try {
          log.info("Processing section {}: {}", section.getSectionIndex(),
              section.getSectionTitle());

          // Fill questions in current (or expected) section
          int filledQuestions = fillQuestionsInSection(driver, section, selections, humanLike);
          int totalQuestionsInSection = section.getQuestions().size();
          log.info("Filled {}/{} questions in section {} (first attempt)", filledQuestions,
              totalQuestionsInSection, section.getSectionIndex());

          // If nothing filled, it may mean we are still on a previous section (e.g., intro page).
          // Try to minimally satisfy current page and navigate to the target section, then retry.
          if (filledQuestions == 0) {
            boolean canProceed = false;
            try {
              log.info(
                  "No filled questions for section {} on first attempt. Trying minimal autofill to navigate...",
                  section.getSectionIndex());
              canProceed = requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
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
              filledQuestions = fillQuestionsInSection(driver, section, selections, humanLike);
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
                "Not all questions filled in section {}: {}/{} questions filled. Attempting to fill remaining questions...",
                section.getSectionIndex(), filledQuestions, totalQuestionsInSection);

            // Try to fill remaining questions with retry logic
            int retryCount = 0;
            int maxRetries = 3;
            while (filledQuestions < totalQuestionsInSection && retryCount < maxRetries) {
              retryCount++;
              log.info("Retry attempt {} to fill remaining questions in section {}", retryCount,
                  section.getSectionIndex());

              // Wait a bit before retry
              try {
                Thread.sleep(1000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }

              int additionalFilled = fillQuestionsInSection(driver, section, selections, humanLike);
              filledQuestions += additionalFilled;
              log.info("Additional {} questions filled in retry {}, total: {}/{}", additionalFilled,
                  retryCount, filledQuestions, totalQuestionsInSection);
            }

            if (filledQuestions < totalQuestionsInSection) {
              log.error(
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

          // Check if this is the last section
          if (section.isLastSection()) {
            // Click Submit button
            if (autoSubmitEnabled) {
              log.info("Reached last section, clicking Submit button");
              clickSubmitButton(driver, wait, humanLike);
            } else {
              log.info("Reached last section, auto-submit disabled");
            }
            break;
          } else {
            // Only proceed if we've filled all expected mapped answers for this section
            int expectedAnswered = 0;
            for (QuestionInfo qi : section.getQuestions()) {
              if (selections.get(qi.getQuestionEntity()) != null)
                expectedAnswered++;
            }
            if (filledQuestions >= expectedAnswered) {
              log.info("Proceeding to next section: filled {} out of {} expected mapped answers",
                  filledQuestions, expectedAnswered);
              clickNextButton(driver, wait, humanLike);
              try {
                Thread.sleep(800);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            } else {
              // As a fallback, try to satisfy required questions minimally to unlock Next
              boolean canProceed = false;
              try {
                canProceed = requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
              } catch (Exception ignore) {
              }
              if (canProceed) {
                log.info("Proceeding after satisfying required questions (mapped filled: {}/{})",
                    filledQuestions, expectedAnswered);
                clickNextButton(driver, wait, humanLike);
                try {
                  Thread.sleep(800);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              } else {
                log.warn(
                    "Staying on section {}: only {}/{} mapped answers filled; not clicking Next",
                    section.getSectionIndex(), filledQuestions, expectedAnswered);
                // Do not click Next to avoid jumping ahead when section incomplete
              }
            }
          }
        } catch (Exception sectionError) {
          log.error("Error processing section {}: {}", section.getSectionIndex(),
              sectionError.getMessage());
          // Continue with next section instead of stopping
          continue;
        }
      }

      log.info("Successfully completed multi-section form fill for request: {}", fillRequestId);
      return true;

    } catch (Exception e) {
      log.error("Error filling multi-section form for request {}: {}", fillRequestId,
          e.getMessage(), e);
      return false;
    } finally {
      if (driver != null) {
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
      Map<Question, QuestionOption> selections, boolean humanLike) {
    int filledCount = 0;

    log.info("Starting to fill questions in section {}: {} with {} questions",
        section.getSectionIndex(), section.getSectionTitle(), section.getQuestions().size());

    for (QuestionInfo questionInfo : section.getQuestions()) {
      Question question = questionInfo.getQuestionEntity();
      QuestionOption option = selections.get(question);

      log.info("Processing question: {} with option: {}", question.getTitle(),
          option != null ? option.getText() : "null");

      if (option != null) {
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
   * Click Next button to move to next section
   */
  private void clickNextButton(WebDriver driver, WebDriverWait wait, boolean humanLike) {
    try {
      log.info("Looking for Next button...");

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

              if ((buttonText.contains("tiếp") || buttonText.contains("next")) || (ariaLabel != null
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
        log.error("Could not find Next button with any selector");
        return; // Don't throw exception, just return
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
      log.info("Clicked Next button");

      // Wait for section change - try multiple approaches
      try {
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
        log.info("Form element found after clicking Next");
      } catch (Exception e) {
        log.warn("Could not detect form element change, continuing anyway");
      }

      log.info("Successfully clicked Next button and moved to next section");
    } catch (Exception e) {
      log.error("Error clicking Next button: {}", e.getMessage());
      throw e;
    }
  }

  /**
   * Click Submit button to submit the form
   */
  private void clickSubmitButton(WebDriver driver, WebDriverWait wait, boolean humanLike) {
    try {
      log.info("Looking for Submit button...");

      // Try multiple selectors for Submit button
      WebElement submitButton = null;
      try {
        submitButton = wait.until(ExpectedConditions.elementToBeClickable(SUBMIT_BUTTON));
        log.info("Found Submit button using primary selector");
      } catch (Exception e) {
        log.warn("Primary Submit button selector failed, trying alternatives");

        // Alternative selectors
        By[] alternativeSelectors = {By.xpath("//div[@role='button' and contains(., 'Gửi')]"),
            By.xpath("//div[@role='button' and contains(., 'Submit')]"),
            By.xpath("//span[contains(text(), 'Gửi')]/ancestor::div[@role='button']"),
            By.xpath("//span[contains(text(), 'Submit')]/ancestor::div[@role='button']")};

        for (By selector : alternativeSelectors) {
          try {
            submitButton = wait.until(ExpectedConditions.elementToBeClickable(selector));
            log.info("Found Submit button using alternative selector: {}", selector);
            break;
          } catch (Exception ignored) {
          }
        }
      }

      if (submitButton == null) {
        log.error("Could not find Submit button with any selector");
        return; // Don't throw exception, just return
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
      log.info("Clicked Submit button");

      // Wait for submission confirmation - try multiple approaches
      try {
        WebDriverWait submitWait = new WebDriverWait(driver, Duration.ofSeconds(10));
        submitWait.until(ExpectedConditions.urlContains("formResponse"));
        log.info("Form submitted successfully - URL contains 'formResponse'");
      } catch (Exception e) {
        log.warn("Could not detect form submission via URL change, but continuing");
      }

      log.info("Successfully clicked Submit button and form submitted");
    } catch (Exception e) {
      log.error("Error clicking Submit button: {}", e.getMessage());
      throw e;
    }
  }

  /**
   * Open browser with optimized settings
   */
  private WebDriver openBrowser(String formUrl, boolean humanLike) {
    log.info("Opening browser for URL: {}", formUrl);

    ChromeOptions options = new ChromeOptions();
    if (headless) {
      options.addArguments("--headless=new");
    }
    options.addArguments("--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage");
    options.addArguments("--disable-blink-features=AutomationControlled");
    options.addArguments("--disable-extensions");

    if (humanLike) {
      // Add user agent for more human-like behavior
      options.addArguments(
          "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
    }

    WebDriver driver = new ChromeDriver(options);

    try {
      log.info("Navigating to form URL...");
      driver.get(formUrl);

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

      // Wait for form to be present
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
      try {
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
        log.info("Form element found on page");
      } catch (Exception e) {
        log.warn("Form element not found, but continuing anyway: {}", e.getMessage());
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
   * Shutdown WebDriver safely
   */
  private void shutdownDriver(WebDriver driver) {
    if (driver != null) {
      try {
        driver.quit();
        log.debug("WebDriver shutdown successfully");
      } catch (Exception e) {
        log.warn("Error shutting down WebDriver: {}", e.getMessage());
      }
    }
  }


}
