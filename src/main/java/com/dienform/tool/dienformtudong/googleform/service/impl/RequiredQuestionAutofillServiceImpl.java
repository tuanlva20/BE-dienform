package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.googleform.handler.ComboboxHandler;
import com.dienform.tool.dienformtudong.googleform.service.RequiredQuestionAutofillService;
import com.dienform.tool.dienformtudong.googleform.service.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Service
@RequiredArgsConstructor
@Slf4j
public class RequiredQuestionAutofillServiceImpl implements RequiredQuestionAutofillService {

  @Value("${google.form.timeout-seconds:30}")
  private int timeoutSeconds;

  // ThreadLocal để track trạng thái autofill cho mỗi section
  private final ThreadLocal<Set<String>> autofilledSections = new ThreadLocal<>();

  // ThreadLocal để track individual elements đã được fill để tránh repetition
  private final ThreadLocal<Set<String>> autofilledElements = new ThreadLocal<>();

  // Cache để track combobox elements đã được xử lý trong session hiện tại
  private final ThreadLocal<ConcurrentHashMap<String, Boolean>> processedComboboxes =
      new ThreadLocal<>();

  // Inject ComboboxHandler for robust combobox handling
  private final ComboboxHandler comboboxHandler;

  @Override
  public boolean satisfyRequiredQuestions(WebDriver driver) {
    try {
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));
      wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));

      // Tạo unique identifier cho section hiện tại
      String currentSectionId = getCurrentSectionIdentifier(driver);

      log.info("Starting to satisfy required questions in current section: {}", currentSectionId);

      // Kiểm tra xem section này đã được autofill chưa
      Set<String> filledSections = autofilledSections.get();
      if (filledSections == null) {
        filledSections = new HashSet<>();
        autofilledSections.set(filledSections);
      }

      if (filledSections.contains(currentSectionId)) {
        log.info("Section {} already autofilled, skipping", currentSectionId);
        return true;
      }

      // Check if all questions are already satisfied before filling
      if (areQuestionsAlreadySatisfied(driver)) {
        log.info("All questions are already satisfied in section {}, marking as autofilled",
            currentSectionId);
        filledSections.add(currentSectionId);
        return true;
      }

      // Check if there are any unfilled required questions
      if (!hasUnfilledRequiredQuestions(driver)) {
        log.info("No unfilled required questions found in section {}, marking as autofilled",
            currentSectionId);
        filledSections.add(currentSectionId);
        return true;
      }

      // Enhanced filling with better error handling and individual element tracking
      boolean fillSuccess = performEnhancedFilling(driver);

      if (fillSuccess) {
        // Mark this section as autofilled
        filledSections.add(currentSectionId);
        log.info("Completed satisfying required questions in section: {}", currentSectionId);
        return true;
      } else {
        log.warn("Failed to satisfy required questions in section: {}", currentSectionId);
        return false;
      }
    } catch (Exception e) {
      log.debug("satisfyRequiredQuestions failed: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Clear autofill tracking (call this when starting a new form)
   */
  public void clearAutofillTracking() {
    autofilledSections.remove();
    autofilledElements.remove();
    processedComboboxes.remove();
  }

  /**
   * Check if the Next button is ready to be clicked (enabled and visible)
   */
  public boolean isNextButtonReady(WebDriver driver) {
    try {
      By nextButtonLocator = By.xpath(
          "//div[@role='button' and (.//span[normalize-space()='Tiếp'] or .//span[normalize-space()='Next'])]");

      List<WebElement> nextButtons = driver.findElements(nextButtonLocator);
      if (nextButtons.isEmpty()) {
        log.debug("No Next button found");
        return false;
      }

      for (WebElement nextButton : nextButtons) {
        if (nextButton.isDisplayed() && nextButton.isEnabled()) {
          // Check if button is not disabled
          String ariaDisabled = nextButton.getAttribute("aria-disabled");
          if (!"true".equals(ariaDisabled)) {
            log.debug("Next button is ready to be clicked");
            return true;
          }
        }
      }

      log.debug("Next button found but not ready (disabled or not visible)");
      return false;
    } catch (Exception e) {
      log.debug("Error checking Next button status: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Enhanced method to check if Next button is ready with additional validation
   */
  public boolean isNextButtonReadyEnhanced(WebDriver driver) {
    try {
      // First check if Next button is ready
      boolean nextReady = isNextButtonReady(driver);

      if (!nextReady) {
        log.debug("Next button not ready, attempting to satisfy required questions");

        // Try to satisfy required questions
        boolean fillSuccess = performEnhancedFilling(driver);

        if (fillSuccess) {
          // Check again after filling
          nextReady = isNextButtonReady(driver);
          if (nextReady) {
            log.info("Next button became ready after satisfying required questions");
          } else {
            log.warn("Next button still not ready after satisfying required questions");
          }
        } else {
          log.warn("Failed to satisfy required questions");
        }
      }

      return nextReady;
    } catch (Exception e) {
      log.error("Error in isNextButtonReadyEnhanced: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Check if we're stuck in the same section (to avoid infinite loops)
   */
  public boolean isStuckInSameSection(WebDriver driver) {
    try {
      // Get current section identifier
      String currentSectionId = getCurrentSectionIdentifier(driver);

      // Check if we've been in this section before
      Set<String> filledSections = autofilledSections.get();
      if (filledSections != null && filledSections.contains(currentSectionId)) {
        log.warn("Detected stuck in same section: {}", currentSectionId);
        return true;
      }

      return false;
    } catch (Exception e) {
      log.debug("Error checking if stuck in same section: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Validate that all required questions are properly filled before proceeding
   */
  public ValidationResult validateRequiredQuestions(WebDriver driver) {
    try {
      log.debug("Validating required questions in current section");

      List<String> missingRequiredQuestions = new ArrayList<>();

      // Check required text inputs
      List<WebElement> requiredTextInputs = driver.findElements(By.cssSelector(
          "input[type='text'][required], input[type='email'][required], textarea[required]"));
      for (WebElement input : requiredTextInputs) {
        if (input.isDisplayed()) {
          String value = input.getAttribute("value");
          if (value == null || value.trim().isEmpty()) {
            String questionTitle = getQuestionTitleForInput(input);
            missingRequiredQuestions.add("Text input: " + questionTitle);
            log.warn("Required text input not filled: {}", questionTitle);
          }
        }
      }

      // Check required radio groups
      List<WebElement> requiredRadioGroups =
          driver.findElements(By.cssSelector("[role='radiogroup'][aria-required='true']"));
      for (WebElement group : requiredRadioGroups) {
        if (group.isDisplayed()) {
          List<WebElement> selectedRadios =
              group.findElements(By.cssSelector("[role='radio'][aria-checked='true']"));
          if (selectedRadios.isEmpty()) {
            String questionTitle = getQuestionTitleForElement(group);
            missingRequiredQuestions.add("Radio group: " + questionTitle);
            log.warn("Required radio group not selected: {}", questionTitle);
          }
        }
      }

      // Check required listboxes (comboboxes)
      List<WebElement> requiredListboxes =
          driver.findElements(By.cssSelector("[role='listbox'][aria-required='true']"));
      for (WebElement listbox : requiredListboxes) {
        if (listbox.isDisplayed()) {
          if (!isListboxAlreadyFilled(listbox)) {
            String questionTitle = getQuestionTitleForElement(listbox);
            missingRequiredQuestions.add("Combobox: " + questionTitle);
            log.warn("Required combobox not selected: {}", questionTitle);
          }
        }
      }

      // Also check listboxes that might not have aria-required but are required
      List<WebElement> allListboxes = driver.findElements(By.cssSelector("[role='listbox']"));
      for (WebElement listbox : allListboxes) {
        if (listbox.isDisplayed()) {
          // Check if this listbox is in a required question (has asterisk)
          try {
            WebElement questionContainer =
                listbox.findElement(By.xpath("./ancestor::div[@role='listitem']"));
            List<WebElement> asterisks = questionContainer.findElements(
                By.xpath(".//span[contains(@class, 'vnumgf') and contains(text(), '*')]"));

            if (!asterisks.isEmpty() && !isListboxAlreadyFilled(listbox)) {
              String questionTitle = getQuestionTitleForElement(listbox);
              missingRequiredQuestions.add("Required combobox: " + questionTitle);
              log.warn("Required combobox (with asterisk) not selected: {}", questionTitle);
            }
          } catch (Exception e) {
            log.debug("Error checking listbox for asterisk: {}", e.getMessage());
          }
        }
      }

      // Check for questions with required asterisk (*) that might not have aria-required
      List<WebElement> questionsWithAsterisk = driver
          .findElements(By.xpath("//span[contains(@class, 'vnumgf') and contains(text(), '*')]"));
      for (WebElement asterisk : questionsWithAsterisk) {
        try {
          // Find the parent question container
          WebElement questionContainer =
              asterisk.findElement(By.xpath("./ancestor::div[@role='listitem']"));
          String questionTitle = getQuestionTitleForElement(questionContainer);

          // Check if this question is answered
          boolean isAnswered = false;

          // Check for text inputs
          List<WebElement> textInputs = questionContainer
              .findElements(By.cssSelector("input[type='text'], input[type='email'], textarea"));
          for (WebElement input : textInputs) {
            String value = input.getAttribute("value");
            if (value != null && !value.trim().isEmpty()) {
              isAnswered = true;
              break;
            }
          }

          // Check for selected radios
          if (!isAnswered) {
            List<WebElement> selectedRadios = questionContainer
                .findElements(By.cssSelector("[role='radio'][aria-checked='true']"));
            isAnswered = !selectedRadios.isEmpty();
          }

          // Check for selected checkboxes
          if (!isAnswered) {
            List<WebElement> selectedCheckboxes = questionContainer
                .findElements(By.cssSelector("[role='checkbox'][aria-checked='true']"));
            isAnswered = !selectedCheckboxes.isEmpty();
          }

          // Check for filled listboxes
          if (!isAnswered) {
            List<WebElement> listboxes =
                questionContainer.findElements(By.cssSelector("[role='listbox']"));
            for (WebElement listbox : listboxes) {
              if (isListboxAlreadyFilled(listbox)) {
                isAnswered = true;
                break;
              }
            }
          }

          if (!isAnswered) {
            missingRequiredQuestions.add("Required question: " + questionTitle);
            log.warn("Required question with asterisk not answered: {}", questionTitle);
          }

        } catch (Exception e) {
          log.debug("Error checking question with asterisk: {}", e.getMessage());
        }
      }

      if (missingRequiredQuestions.isEmpty()) {
        log.debug("All required questions are properly filled");
        return ValidationResult.success();
      } else {
        String errorMessage = String.format("Missing %d required questions: %s",
            missingRequiredQuestions.size(), String.join(", ", missingRequiredQuestions));
        log.error("Validation failed: {}", errorMessage);
        return ValidationResult.failure(errorMessage, missingRequiredQuestions);
      }

    } catch (Exception e) {
      String errorMessage = "Error validating required questions: " + e.getMessage();
      log.error(errorMessage, e);
      return ValidationResult.failure(errorMessage, List.of("Validation error"));
    }
  }

  /**
   * Get question title for input element
   */
  private String getQuestionTitleForInput(WebElement input) {
    try {
      // Find the parent question container
      WebElement questionContainer =
          input.findElement(By.xpath("./ancestor::div[@role='listitem']"));
      return getQuestionTitleForElement(questionContainer);
    } catch (Exception e) {
      return "Unknown input question";
    }
  }

  /**
   * Perform enhanced filling with better error handling and validation
   */
  private boolean performEnhancedFilling(WebDriver driver) {
    try {
      int totalFilled = 0;
      int totalAttempted = 0;

      // Text inputs - fill only unfilled text inputs
      int textFilled = fillUnfilledTextInputs(driver, By.cssSelector("input[type='text']"), "N/A");
      totalFilled += textFilled;
      totalAttempted += textFilled;

      int emailFilled =
          fillUnfilledTextInputs(driver, By.cssSelector("input[type='email']"), "test@example.com");
      totalFilled += emailFilled;
      totalAttempted += emailFilled;

      int textareaFilled = fillUnfilledTextInputs(driver, By.tagName("textarea"), "N/A");
      totalFilled += textareaFilled;
      totalAttempted += textareaFilled;

      // Radio: pick first option for EACH unfilled radio group
      int radioFilled = clickFirstInEachUnfilledRadioGroup(driver);
      totalFilled += radioFilled;
      totalAttempted += radioFilled;

      // Checkbox: tick first option for EACH unfilled checkbox group
      int checkboxFilled = clickFirstInEachUnfilledCheckboxGroup(driver);
      totalFilled += checkboxFilled;
      totalAttempted += checkboxFilled;

      // Combobox/Listbox: open then select first option for EACH unfilled listbox
      int comboboxFilled = fillUnfilledListboxesEnhanced(driver);
      totalFilled += comboboxFilled;
      totalAttempted += comboboxFilled;

      // Multiple choice grid: for each row pick first radio
      int gridRadioFilled =
          tryClickFirstInEachUnfilledRow(driver, By.cssSelector("[role='radio']"));
      totalFilled += gridRadioFilled;
      totalAttempted += gridRadioFilled;

      // Checkbox grid: pick at least one checkbox per row
      int gridCheckboxFilled =
          tryClickFirstInEachUnfilledRow(driver, By.cssSelector("[role='checkbox']"));
      totalFilled += gridCheckboxFilled;
      totalAttempted += gridCheckboxFilled;

      log.info("Enhanced filling completed: {}/{} elements filled successfully", totalFilled,
          totalAttempted);

      // Return true if at least some elements were filled or if no elements were found
      return totalFilled > 0 || totalAttempted == 0;

    } catch (Exception e) {
      log.error("Error in performEnhancedFilling: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Enhanced version of fillAllListboxes that returns count of filled elements
   */
  private int fillAllListboxesEnhanced(WebDriver driver) {
    try {
      List<WebElement> listboxes = driver.findElements(By.cssSelector("[role='listbox']"));
      int filledListboxes = 0;
      int totalListboxes = listboxes.size();

      log.info("Found {} listboxes to fill", totalListboxes);

      for (int i = 0; i < listboxes.size(); i++) {
        WebElement lb = listboxes.get(i);
        if (lb.isDisplayed()) {
          try {
            log.debug("Processing listbox {}/{}", i + 1, totalListboxes);

            // Get question title for better logging
            String questionTitle = getQuestionTitleForElement(lb);

            // Use enhanced combobox filling with better click logic
            String elementId = generateElementIdentifier(lb, i);
            boolean success = fillSingleListboxEnhanced(driver, lb, questionTitle, elementId);

            if (success) {
              filledListboxes++;
              log.debug("Successfully filled listbox {}/{}: {}", i + 1, totalListboxes,
                  questionTitle);
            } else {
              log.warn("Failed to fill listbox {}/{}: {}", i + 1, totalListboxes, questionTitle);
            }

            // Small delay between listboxes
            try {
              Thread.sleep(300);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }

          } catch (Exception e) {
            log.warn("Exception filling listbox {}/{}: {}", i + 1, totalListboxes, e.getMessage());
          }
        }
      }

      if (filledListboxes > 0) {
        log.info("Successfully filled {}/{} listboxes", filledListboxes, totalListboxes);
      } else {
        log.warn("Failed to fill any listboxes out of {} found", totalListboxes);
      }

      return filledListboxes;
    } catch (Exception e) {
      log.error("Error filling listboxes: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Tạo unique identifier cho section hiện tại dựa trên URL và các element hiện có
   */
  private String getCurrentSectionIdentifier(WebDriver driver) {
    try {
      String currentUrl = driver.getCurrentUrl();

      // Lấy tất cả question titles trong section hiện tại
      List<WebElement> questionElements =
          driver.findElements(By.cssSelector("div[role='listitem']"));
      StringBuilder sectionContent = new StringBuilder();
      int questionCount = 0;

      for (WebElement element : questionElements) {
        try {
          List<WebElement> titleElements = element.findElements(By.cssSelector("[role='heading']"));
          if (!titleElements.isEmpty()) {
            String title = titleElements.get(0).getText().trim();
            if (!title.isEmpty()) {
              sectionContent.append(title).append("|");
              questionCount++;
            }
          }
        } catch (Exception e) {
          // Ignore individual element errors
        }
      }

      // Also include current page source hash for better uniqueness
      String pageSourceSnippet = "";
      try {
        String pageSource = driver.getPageSource();
        if (pageSource.length() > 1000) {
          pageSourceSnippet = pageSource.substring(0, 1000);
        } else {
          pageSourceSnippet = pageSource;
        }
      } catch (Exception e) {
        log.debug("Failed to get page source snippet: {}", e.getMessage());
      }

      // Include timestamp to differentiate between rapid reloads of same content
      long timestamp = System.currentTimeMillis() / 1000; // Second precision

      // Tạo hash từ URL, content, question count, page snippet và timestamp
      String content = currentUrl + "|" + sectionContent.toString() + "|Q:" + questionCount + "|PS:"
          + pageSourceSnippet.hashCode() + "|T:" + timestamp;
      String sectionId = String.valueOf(content.hashCode());

      log.debug("Generated section ID: {} for {} questions", sectionId, questionCount);
      return sectionId;
    } catch (Exception e) {
      // Fallback to URL hash with timestamp if content extraction fails
      long timestamp = System.currentTimeMillis();
      String fallbackId = String.valueOf((driver.getCurrentUrl() + "|" + timestamp).hashCode());
      log.debug("Using fallback section ID: {}", fallbackId);
      return fallbackId;
    }
  }

  /**
   * Check if there are any unfilled required questions in the current section
   */
  private boolean hasUnfilledRequiredQuestions(WebDriver driver) {
    try {
      // Check text inputs
      List<WebElement> textInputs =
          driver.findElements(By.cssSelector("input[type='text'], input[type='email'], textarea"));
      for (WebElement input : textInputs) {
        if (input.isDisplayed() && input.isEnabled()) {
          String value = input.getAttribute("value");
          if (value == null || value.trim().isEmpty()) {
            log.debug("Found unfilled text input");
            return true;
          }
        }
      }

      // Check radio groups
      List<WebElement> radioGroups = driver.findElements(By.cssSelector("[role='radiogroup']"));
      for (WebElement group : radioGroups) {
        if (group.isDisplayed()) {
          List<WebElement> radios = group.findElements(By.cssSelector("[role='radio']"));
          boolean groupAnswered = false;
          for (WebElement radio : radios) {
            if ("true".equals(radio.getAttribute("aria-checked"))) {
              groupAnswered = true;
              break;
            }
          }
          if (!groupAnswered) {
            log.debug("Found unfilled radio group");
            return true;
          }
        }
      }

      // Check checkboxes
      List<WebElement> checkboxes = driver.findElements(By.cssSelector("[role='checkbox']"));
      for (WebElement checkbox : checkboxes) {
        if (checkbox.isDisplayed()) {
          if (!"true".equals(checkbox.getAttribute("aria-checked"))) {
            log.debug("Found unfilled checkbox");
            return true;
          }
        }
      }

      // Check listboxes
      List<WebElement> listboxes = driver.findElements(By.cssSelector("[role='listbox']"));
      for (WebElement listbox : listboxes) {
        if (listbox.isDisplayed()) {
          String selectedValue = listbox.getAttribute("aria-label");
          if (selectedValue == null || selectedValue.trim().isEmpty()
              || selectedValue.toLowerCase().contains("select")
              || selectedValue.toLowerCase().contains("chọn")) {
            log.debug("Found unfilled listbox");
            return true;
          }
        }
      }

      log.debug("No unfilled required questions found");
      return false;
    } catch (Exception e) {
      log.debug("Error checking for unfilled required questions: {}", e.getMessage());
      return true; // If we can't determine, assume there are unfilled questions
    }
  }

  /**
   * Check if questions are already satisfied (have answers) to avoid unnecessary filling
   */
  private boolean areQuestionsAlreadySatisfied(WebDriver driver) {
    try {
      int totalQuestions = 0;
      int answeredQuestions = 0;

      // Check text inputs
      List<WebElement> textInputs =
          driver.findElements(By.cssSelector("input[type='text'], input[type='email'], textarea"));
      for (WebElement input : textInputs) {
        if (input.isDisplayed() && input.isEnabled()) {
          totalQuestions++;
          String value = input.getAttribute("value");
          if (value != null && !value.trim().isEmpty()) {
            answeredQuestions++;
          }
        }
      }

      // Check radio groups (each group counts as one question)
      List<WebElement> radioGroups = driver.findElements(By.cssSelector("[role='radiogroup']"));
      for (WebElement group : radioGroups) {
        if (group.isDisplayed()) {
          totalQuestions++;
          List<WebElement> radios = group.findElements(By.cssSelector("[role='radio']"));
          boolean groupAnswered = false;
          for (WebElement radio : radios) {
            if ("true".equals(radio.getAttribute("aria-checked"))) {
              groupAnswered = true;
              break;
            }
          }
          if (groupAnswered) {
            answeredQuestions++;
          }
        }
      }

      // Check checkboxes (each checkbox counts as one question)
      List<WebElement> checkboxes = driver.findElements(By.cssSelector("[role='checkbox']"));
      for (WebElement checkbox : checkboxes) {
        if (checkbox.isDisplayed()) {
          totalQuestions++;
          if ("true".equals(checkbox.getAttribute("aria-checked"))) {
            answeredQuestions++;
          }
        }
      }

      // Check listboxes
      List<WebElement> listboxes = driver.findElements(By.cssSelector("[role='listbox']"));
      for (WebElement listbox : listboxes) {
        if (listbox.isDisplayed()) {
          totalQuestions++;
          // Check if listbox has a selected value (not empty)
          String selectedValue = listbox.getAttribute("aria-label");
          if (selectedValue != null && !selectedValue.trim().isEmpty()
              && !selectedValue.toLowerCase().contains("select")
              && !selectedValue.toLowerCase().contains("chọn")) {
            answeredQuestions++;
          }
        }
      }

      // Check if all questions are answered
      boolean allQuestionsAnswered = totalQuestions > 0 && answeredQuestions == totalQuestions;

      log.info("Questions satisfaction check: total={}, answered={}, allAnswered={}",
          totalQuestions, answeredQuestions, allQuestionsAnswered);

      return allQuestionsAnswered;
    } catch (Exception e) {
      log.debug("Error checking if questions are already satisfied: {}", e.getMessage());
      return false; // If we can't determine, assume not satisfied
    }
  }

  /**
   * Fill only unfilled text inputs matching the locator
   */
  private int fillUnfilledTextInputs(WebDriver driver, By locator, String value) {
    try {
      List<WebElement> elements = driver.findElements(locator);
      int filledCount = 0;
      int totalElements = elements.size();

      log.info("Found {} text input elements to check", totalElements);

      for (int i = 0; i < elements.size(); i++) {
        WebElement el = elements.get(i);
        if (el.isDisplayed() && el.isEnabled()) {
          try {
            // Check if element already has content
            String currentValue = el.getAttribute("value");
            if (currentValue == null || currentValue.trim().isEmpty()) {
              el.clear();
              el.sendKeys(value);
              filledCount++;
              log.debug("Filled unfilled text input {} of {}: {}", i + 1, totalElements, value);
            } else {
              log.debug("Text input {} of {} already has content: '{}', skipping", i + 1,
                  totalElements, currentValue);
            }
          } catch (Exception e) {
            log.debug("Failed to fill text input {} of {}: {}", i + 1, totalElements,
                e.getMessage());
          }
        } else {
          log.debug("Text input {} of {} not visible or not enabled, skipping", i + 1,
              totalElements);
        }
      }

      if (filledCount > 0) {
        log.info("Filled {} unfilled text inputs with value: '{}'", filledCount, value);
      } else {
        log.info("No unfilled text inputs were found");
      }

      return filledCount;
    } catch (Exception e) {
      log.debug("Error filling text inputs: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Fill ALL visible elements matching the locator
   */
  private int fillAllVisible(WebDriver driver, By locator, String value) {
    try {
      List<WebElement> elements = driver.findElements(locator);
      int filledCount = 0;
      int totalElements = elements.size();

      log.info("Found {} text input elements to fill", totalElements);

      for (int i = 0; i < elements.size(); i++) {
        WebElement el = elements.get(i);
        if (el.isDisplayed() && el.isEnabled()) {
          try {
            // Check if element already has content
            String currentValue = el.getAttribute("value");
            if (currentValue == null || currentValue.trim().isEmpty()) {
              el.clear();
              el.sendKeys(value);
              filledCount++;
              log.debug("Filled text input {} of {}: {}", i + 1, totalElements, value);
            } else {
              log.debug("Text input {} of {} already has content: '{}', skipping", i + 1,
                  totalElements, currentValue);
            }
          } catch (Exception e) {
            log.debug("Failed to fill text input {} of {}: {}", i + 1, totalElements,
                e.getMessage());
          }
        } else {
          log.debug("Text input {} of {} not visible or not enabled, skipping", i + 1,
              totalElements);
        }
      }

      if (filledCount > 0) {
        log.info("Filled {} text inputs with value: '{}'", filledCount, value);
      } else {
        log.info("No text inputs were filled (all may have had content or were not fillable)");
      }

      return filledCount;
    } catch (Exception e) {
      log.debug("Error filling text inputs: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Click first option in EACH unfilled radio group (always select first option)
   */
  private int clickFirstInEachUnfilledRadioGroup(WebDriver driver) {
    try {
      // Find all radio groups
      List<WebElement> radioGroups = driver.findElements(By.cssSelector("[role='radiogroup']"));
      int clickedGroups = 0;

      log.info("Found {} radio groups to check", radioGroups.size());

      for (WebElement group : radioGroups) {
        if (group.isDisplayed()) {
          // Check if this group already has a selected radio
          List<WebElement> radios = group.findElements(By.cssSelector("[role='radio']"));
          boolean groupAlreadyAnswered = false;
          for (WebElement radio : radios) {
            if ("true".equals(radio.getAttribute("aria-checked"))) {
              groupAlreadyAnswered = true;
              break;
            }
          }

          // Only fill if group is not already answered
          if (!groupAlreadyAnswered) {
            log.debug("Found unfilled radio group, attempting to fill");
            for (WebElement radio : radios) {
              if (radio.isDisplayed() && radio.isEnabled()) {
                try {
                  radio.click();
                  clickedGroups++;
                  log.debug("Clicked first radio in unfilled group {} of {}", clickedGroups,
                      radioGroups.size());

                  // Add a small delay between clicks to avoid interference
                  try {
                    Thread.sleep(100);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  break; // Only click one per group
                } catch (Exception e) {
                  log.debug("Failed to click radio in group: {}", e.getMessage());
                }
              }
            }
          } else {
            log.debug("Radio group already answered, skipping");
          }
        } else {
          log.debug("Radio group not visible, skipping");
        }
      }

      if (clickedGroups > 0) {
        log.info("Clicked first radio in {} unfilled radio groups", clickedGroups);
      } else {
        log.info("No unfilled radio groups were found");
      }

      return clickedGroups;
    } catch (Exception e) {
      log.debug("Error clicking radio groups: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Click first option in EACH radio group (not just the first radio found)
   */
  private int clickFirstInEachRadioGroup(WebDriver driver) {
    try {
      // Find all radio groups
      List<WebElement> radioGroups = driver.findElements(By.cssSelector("[role='radiogroup']"));
      int clickedGroups = 0;

      log.info("Found {} radio groups in current section", radioGroups.size());

      for (WebElement group : radioGroups) {
        if (group.isDisplayed()) {
          // Find the first radio button in this group
          List<WebElement> radios = group.findElements(By.cssSelector("[role='radio']"));
          log.debug("Found {} radio buttons in group", radios.size());

          for (WebElement radio : radios) {
            if (radio.isDisplayed() && radio.isEnabled()) {
              try {
                // Check if this radio is already selected
                String ariaChecked = radio.getAttribute("aria-checked");
                if (!"true".equals(ariaChecked)) {
                  radio.click();
                  clickedGroups++;
                  log.debug("Clicked first radio in group {} of {}", clickedGroups,
                      radioGroups.size());

                  // Add a small delay between clicks to avoid interference
                  try {
                    Thread.sleep(100);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  break; // Only click one per group
                } else {
                  log.debug("Radio already selected, skipping");
                  clickedGroups++; // Count as processed even if already selected
                  break;
                }
              } catch (Exception e) {
                log.debug("Failed to click radio in group: {}", e.getMessage());
              }
            }
          }
        } else {
          log.debug("Radio group not visible, skipping");
        }
      }

      if (clickedGroups > 0) {
        log.info("Processed {} radio groups (clicked or already selected)", clickedGroups);
      } else {
        log.info("No radio groups were processed");
      }

      return clickedGroups;
    } catch (Exception e) {
      log.debug("Error clicking radio groups: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Click first option in EACH unfilled checkbox group (always select first option)
   */
  private int clickFirstInEachUnfilledCheckboxGroup(WebDriver driver) {
    try {
      // Find all checkbox groups by looking for containers with multiple checkboxes
      List<WebElement> allCheckboxes = driver.findElements(By.cssSelector("[role='checkbox']"));
      int clickedCheckboxes = 0;

      log.info("Found {} total checkboxes to check", allCheckboxes.size());

      // Group checkboxes by their container to avoid clicking multiple in same group
      for (WebElement checkbox : allCheckboxes) {
        if (checkbox.isDisplayed() && checkbox.isEnabled()) {
          try {
            // Check if this checkbox is already selected
            String ariaChecked = checkbox.getAttribute("aria-checked");
            if (!"true".equals(ariaChecked)) {
              checkbox.click();
              clickedCheckboxes++;
              log.debug("Clicked unfilled checkbox {} of {}", clickedCheckboxes,
                  allCheckboxes.size());

              // Add a small delay between clicks to avoid interference
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            } else {
              log.debug("Checkbox already selected, skipping");
            }
          } catch (Exception e) {
            log.debug("Failed to click checkbox: {}", e.getMessage());
          }
        } else {
          log.debug("Checkbox not visible or not enabled, skipping");
        }
      }

      if (clickedCheckboxes > 0) {
        log.info("Clicked {} unfilled checkboxes out of {} total", clickedCheckboxes,
            allCheckboxes.size());
      } else {
        log.info("No unfilled checkboxes were found");
      }

      return clickedCheckboxes;
    } catch (Exception e) {
      log.debug("Error clicking checkboxes: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Enhanced version of fillAllListboxes that only fills unfilled listboxes
   */
  private int fillUnfilledListboxesEnhanced(WebDriver driver) {
    try {
      List<WebElement> listboxElements = driver.findElements(By.cssSelector("[role='listbox']"));
      int totalListboxes = listboxElements.size();
      int filledListboxes = 0;

      log.info("Found {} listbox elements to check", totalListboxes);

      // Initialize element tracking if needed
      Set<String> filledElements = autofilledElements.get();
      if (filledElements == null) {
        filledElements = new HashSet<>();
        autofilledElements.set(filledElements);
      }

      ConcurrentHashMap<String, Boolean> processedComboboxCache = processedComboboxes.get();
      if (processedComboboxCache == null) {
        processedComboboxCache = new ConcurrentHashMap<>();
        processedComboboxes.set(processedComboboxCache);
      }

      for (int i = 0; i < listboxElements.size(); i++) {
        WebElement listboxElement = listboxElements.get(i);
        if (!listboxElement.isDisplayed()) {
          log.debug("Listbox {} of {} not visible, skipping", i + 1, totalListboxes);
          continue;
        }

        try {
          // Generate unique identifier for this specific listbox element
          String elementId = generateElementIdentifier(listboxElement, i);

          // Check if this specific element has already been processed
          if (processedComboboxCache.containsKey(elementId)) {
            log.debug("Listbox element {} already processed, skipping", elementId);
            continue;
          }

          // Check if listbox is already filled
          if (isListboxAlreadyFilled(listboxElement)) {
            log.debug("Listbox {} of {} already filled, marking as processed", i + 1,
                totalListboxes);
            processedComboboxCache.put(elementId, true);
            continue;
          }

          String questionTitle = getQuestionTitleForElement(listboxElement);
          log.info("Processing listbox {} of {}: {}", i + 1, totalListboxes, questionTitle);

          // Enhanced filling with individual element tracking
          boolean success =
              fillSingleListboxEnhanced(driver, listboxElement, questionTitle, elementId);

          if (success) {
            filledListboxes++;
            processedComboboxCache.put(elementId, true);
            log.info("Successfully filled listbox {} of {}: {}", i + 1, totalListboxes,
                questionTitle);
          } else {
            log.warn("Failed to fill listbox {} of {}: {}", i + 1, totalListboxes, questionTitle);
            // Mark as processed even if failed to avoid infinite retries
            processedComboboxCache.put(elementId, false);
          }

          // Small delay between listboxes
          try {
            Thread.sleep(300);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }

        } catch (Exception e) {
          log.debug("Error processing listbox {} of {}: {}", i + 1, totalListboxes, e.getMessage());
        }
      }

      if (filledListboxes > 0) {
        log.info("Successfully filled {}/{} listboxes", filledListboxes, totalListboxes);
      } else {
        log.warn("Failed to fill any listboxes out of {} found", totalListboxes);
      }

      return filledListboxes;
    } catch (Exception e) {
      log.error("Error filling listboxes: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Try to click first option in each unfilled row
   */
  private int tryClickFirstInEachUnfilledRow(WebDriver driver, By optionLocator) {
    try {
      // For grid questions, we need to click one option per row
      // Find all radio groups (which represent rows in grids)
      List<WebElement> radioGroups = driver.findElements(By.cssSelector("[role='radiogroup']"));
      int clickedRows = 0;

      for (WebElement group : radioGroups) {
        if (group.isDisplayed()) {
          // Check if this row already has a selected option
          List<WebElement> options = group.findElements(optionLocator);
          boolean rowAlreadyAnswered = false;
          for (WebElement option : options) {
            if ("true".equals(option.getAttribute("aria-checked"))) {
              rowAlreadyAnswered = true;
              break;
            }
          }

          // Only fill if row is not already answered
          if (!rowAlreadyAnswered) {
            log.debug("Found unfilled grid row, attempting to fill");
            for (WebElement option : options) {
              if (option.isDisplayed() && option.isEnabled()) {
                try {
                  option.click();
                  clickedRows++;
                  log.debug("Clicked first option in unfilled grid row");
                  break; // Only click one per row
                } catch (Exception e) {
                  log.debug("Failed to click grid option: {}", e.getMessage());
                }
              }
            }
          } else {
            log.debug("Grid row already answered, skipping");
          }
        }
      }

      if (clickedRows > 0) {
        log.info("Clicked first option in {} unfilled grid rows", clickedRows);
      } else {
        log.info("No unfilled grid rows were found");
      }

      return clickedRows;
    } catch (Exception e) {
      log.debug("Error clicking grid options: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Click first option in EACH checkbox group (not just the first checkbox found)
   */
  private int clickFirstInEachCheckboxGroup(WebDriver driver) {
    try {
      // Find all checkbox groups by looking for containers with multiple checkboxes
      List<WebElement> allCheckboxes = driver.findElements(By.cssSelector("[role='checkbox']"));
      int clickedCheckboxes = 0;

      log.info("Found {} total checkboxes in current section", allCheckboxes.size());

      // Group checkboxes by their container to avoid clicking multiple in same group
      for (WebElement checkbox : allCheckboxes) {
        if (checkbox.isDisplayed() && checkbox.isEnabled()) {
          try {
            // Check if this checkbox is already selected
            String ariaChecked = checkbox.getAttribute("aria-checked");
            if (!"true".equals(ariaChecked)) {
              checkbox.click();
              clickedCheckboxes++;
              log.debug("Clicked checkbox {} of {}", clickedCheckboxes, allCheckboxes.size());

              // Add a small delay between clicks to avoid interference
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            } else {
              log.debug("Checkbox already selected, skipping");
            }
          } catch (Exception e) {
            log.debug("Failed to click checkbox: {}", e.getMessage());
          }
        } else {
          log.debug("Checkbox not visible or not enabled, skipping");
        }
      }

      if (clickedCheckboxes > 0) {
        log.info("Clicked {} checkboxes out of {} total", clickedCheckboxes, allCheckboxes.size());
      } else {
        log.info(
            "No checkboxes were clicked (all may have been already selected or not clickable)");
      }

      return clickedCheckboxes;
    } catch (Exception e) {
      log.debug("Error clicking checkboxes: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Fill ALL listboxes/comboboxes in the current section using robust ComboboxHandler
   */
  private void fillAllListboxes(WebDriver driver) {
    try {
      List<WebElement> listboxes = driver.findElements(By.cssSelector("[role='listbox']"));
      int filledListboxes = 0;
      int totalListboxes = listboxes.size();

      log.info("Found {} listboxes to fill", totalListboxes);

      for (int i = 0; i < listboxes.size(); i++) {
        WebElement lb = listboxes.get(i);
        if (lb.isDisplayed()) {
          try {
            log.debug("Processing listbox {}/{}", i + 1, totalListboxes);

            // Get question title for better logging
            String questionTitle = getQuestionTitleForElement(lb);

            // Use enhanced combobox filling with better click logic
            String elementId = generateElementIdentifier(lb, i);
            boolean success = fillSingleListboxEnhanced(driver, lb, questionTitle, elementId);

            if (success) {
              filledListboxes++;
              log.debug("Successfully filled listbox {}/{}: {}", i + 1, totalListboxes,
                  questionTitle);
            } else {
              log.warn("Failed to fill listbox {}/{}: {}", i + 1, totalListboxes, questionTitle);
            }

            // Small delay between listboxes
            try {
              Thread.sleep(300);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }

          } catch (Exception e) {
            log.warn("Exception filling listbox {}/{}: {}", i + 1, totalListboxes, e.getMessage());
          }
        }
      }

      if (filledListboxes > 0) {
        log.info("Successfully filled {}/{} listboxes", filledListboxes, totalListboxes);
      } else {
        log.warn("Failed to fill any listboxes out of {} found", totalListboxes);
      }
    } catch (Exception e) {
      log.error("Error filling listboxes: {}", e.getMessage());
    }
  }

  /**
   * Enhanced method for filling a single listbox with improved click and selection logic
   */
  private boolean fillSingleListboxEnhanced(WebDriver driver, WebElement listboxElement,
      String questionTitle, String elementId) {
    try {
      log.info("Attempting to fill listbox for question: {}", questionTitle);

      // Check if listbox is already filled
      if (isListboxAlreadyFilled(listboxElement)) {
        log.info("Listbox already filled for: {}", questionTitle);
        return true;
      }

      // Always ensure dropdown is expanded before selecting any option
      ensureDropdownExpanded(driver, listboxElement, questionTitle);

      // Strategy 1: Use robust ComboboxHandler with tabindex=0 priority
      log.debug("Strategy 1: Using ComboboxHandler with tabindex=0 priority for: {}",
          questionTitle);
      String preferredOptionText = getPreferredOptionText(driver, listboxElement);
      if (preferredOptionText != null && !preferredOptionText.trim().isEmpty()) {
        log.info("Found preferred option '{}' for question: {}", preferredOptionText,
            questionTitle);
        boolean success = comboboxHandler.fillComboboxQuestion(driver, listboxElement,
            questionTitle, preferredOptionText, false);
        if (success) {
          log.info("Successfully filled listbox using ComboboxHandler for: {}", questionTitle);
          return true;
        } else {
          log.warn("ComboboxHandler failed for option '{}' on question: {}", preferredOptionText,
              questionTitle);
        }
      } else {
        log.warn("No preferred options found for question: {}", questionTitle);
      }

      // Strategy 2: Try to expand dropdown and select option directly
      log.debug("Strategy 2: Direct dropdown expansion for: {}", questionTitle);
      boolean success = expandAndSelectOptionDirectly(driver, listboxElement, questionTitle);
      if (success) {
        log.info("Successfully filled listbox using direct expansion for: {}", questionTitle);
        return true;
      }

      // Strategy 3: Use default option if no options found
      log.debug("Strategy 3: Using default option for: {}", questionTitle);
      String defaultOption = getDefaultOptionForQuestion(questionTitle);
      if (defaultOption != null) {
        log.info("Trying default option '{}' for question: {}", defaultOption, questionTitle);
        success = comboboxHandler.fillComboboxQuestion(driver, listboxElement, questionTitle,
            defaultOption, false);
        if (success) {
          log.info("Successfully filled listbox using default option for: {}", questionTitle);
          return true;
        }
      }

      // Strategy 4: Force-click any available option as last resort
      log.debug("Strategy 4: Force-click fallback for: {}", questionTitle);
      success = forceClickAnyOption(driver, listboxElement, questionTitle);
      if (success) {
        log.info("Successfully filled listbox using force-click for: {}", questionTitle);
        return true;
      }

      log.error("All strategies failed for listbox question: {}", questionTitle);
      return false;

    } catch (Exception e) {
      log.error("Error in fillSingleListboxEnhanced for {}: {}", questionTitle, e.getMessage(), e);
      return false;
    }
  }

  /**
   * Expand dropdown and select option directly with enhanced click logic
   */
  private boolean expandAndSelectOptionDirectly(WebDriver driver, WebElement listboxElement,
      String questionTitle) {
    try {
      log.debug("Attempting to expand and select option directly for: {}", questionTitle);

      // Step 1: Ensure dropdown is expanded and popup is open
      if (!ensureDropdownExpanded(driver, listboxElement, questionTitle)) {
        return false;
      }

      // Step 2: Scope options to the nearest popup for this listbox
      WebElement popupContainer = findNearestVisiblePopup(driver, listboxElement);
      if (popupContainer == null) {
        log.debug("No popup container found after expanding dropdown");
        return false;
      }
      List<WebElement> options = popupContainer.findElements(By.cssSelector("[role='option']"));

      // Step 3: Find the preferred clickable option (prioritize tabindex="0", exclude placeholders)
      WebElement selectedOption = null;

      // Filter out placeholder options first
      List<WebElement> validOptions = new ArrayList<>();
      for (WebElement option : options) {
        if (option.isDisplayed()) {
          String dataValue = option.getAttribute("data-value");
          String optionText = getOptionText(option);

          // Skip placeholder options
          if (dataValue == null || dataValue.trim().isEmpty() || "chọn".equalsIgnoreCase(optionText)
              || "select".equalsIgnoreCase(optionText)) {
            log.debug("Skipping placeholder option: data-value='{}', text='{}'", dataValue,
                optionText);
            continue;
          }

          validOptions.add(option);
        }
      }

      log.debug("Found {} valid options (excluding placeholders) for: {}", validOptions.size(),
          questionTitle);

      // First, try to find option with tabindex="0" (most preferred)
      for (WebElement option : validOptions) {
        String tabindex = option.getAttribute("tabindex");
        if ("0".equals(tabindex)) {
          selectedOption = option;
          log.debug("Found preferred option with tabindex=0 for: {}", questionTitle);
          break;
        }
      }

      // If no tabindex="0" found, try to find option with tabindex="1" (second priority)
      if (selectedOption == null) {
        for (WebElement option : validOptions) {
          String tabindex = option.getAttribute("tabindex");
          if ("1".equals(tabindex)) {
            selectedOption = option;
            log.debug("Found option with tabindex=1 for: {}", questionTitle);
            break;
          }
        }
      }

      // If still no preferred option found, use the first valid option
      if (selectedOption == null && !validOptions.isEmpty()) {
        selectedOption = validOptions.get(0);
        log.debug("Using first valid option for: {}", questionTitle);
      }

      if (selectedOption == null) {
        log.debug("No clickable options found for: {}", questionTitle);
        return false;
      }

      // Step 5: Try multiple click strategies
      return clickOptionWithMultipleStrategies(driver, selectedOption, questionTitle);

    } catch (Exception e) {
      log.debug("Error in expandAndSelectOptionDirectly for {}: {}", questionTitle, e.getMessage());
      return false;
    }
  }

  /**
   * Ensure the listbox dropdown is expanded (aria-expanded=true) and popup is open
   */
  private boolean ensureDropdownExpanded(WebDriver driver, WebElement listboxElement,
      String questionTitle) {
    try {
      String ariaExpanded = listboxElement.getAttribute("aria-expanded");
      if (!"true".equals(ariaExpanded)) {
        try {
          listboxElement.click();
          Thread.sleep(300);
        } catch (Exception ignore) {
        }
      }

      ariaExpanded = listboxElement.getAttribute("aria-expanded");
      if (!"true".equals(ariaExpanded)) {
        try {
          ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();",
              listboxElement);
          Thread.sleep(200);
        } catch (Exception ignore) {
        }
      }

      // Final check
      ariaExpanded = listboxElement.getAttribute("aria-expanded");
      if (!"true".equals(ariaExpanded)) {
        log.debug("Failed to expand dropdown for: {}", questionTitle);
        return false;
      }

      return true;
    } catch (Exception e) {
      log.debug("Error ensuring dropdown expanded for {}: {}", questionTitle, e.getMessage());
      return false;
    }
  }

  /**
   * Find the nearest visible popup container corresponding to the provided listbox element. Uses
   * spatial proximity to the trigger to avoid selecting options from other comboboxes.
   */
  private WebElement findNearestVisiblePopup(WebDriver driver, WebElement listboxElement) {
    try {
      org.openqa.selenium.Point triggerLocation = listboxElement.getLocation();

      List<WebElement> candidates = driver.findElements(By.xpath(
          "//div[@role='presentation' and not(contains(@style,'display: none')) and not(contains(@style,'visibility: hidden')) and .//*[@role='option']]"));

      WebElement closest = null;
      double minDistance = Double.MAX_VALUE;
      for (WebElement popup : candidates) {
        try {
          org.openqa.selenium.Point p = popup.getLocation();
          double dx = triggerLocation.getX() - p.getX();
          double dy = triggerLocation.getY() - p.getY();
          double dist = Math.sqrt(dx * dx + dy * dy);
          if (dist < minDistance) {
            minDistance = dist;
            closest = popup;
          }
        } catch (Exception ignore) {
        }
      }

      if (closest != null) {
        log.debug("Nearest popup distance: {:.2f}", minDistance);
      } else {
        log.debug("No visible popup candidates found near listbox");
      }

      return closest;
    } catch (Exception e) {
      log.debug("Error finding nearest popup: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Click option using multiple strategies (similar to ComboboxHandler)
   */
  private boolean clickOptionWithMultipleStrategies(WebDriver driver, WebElement targetOption,
      String questionTitle) {
    try {
      // Ensure the option is in view
      try {
        ((org.openqa.selenium.JavascriptExecutor) driver)
            .executeScript("arguments[0].scrollIntoView({block: 'center'});", targetOption);
      } catch (Exception ignore) {
      }

      // Strategy 1: Try clicking the span element with jsslot attribute (stable)
      try {
        WebElement spanElement = targetOption.findElement(By.cssSelector("span[jsslot]"));
        ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();",
            spanElement);
        log.info("Selected option for question: {} using span[jsslot] click", questionTitle);
        return true;
      } catch (Exception e) {
        log.debug("Span[jsslot] click failed: {}", e.getMessage());
      }

      // Strategy 2: Try JavaScript click on the option element
      try {
        ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();",
            targetOption);
        log.info("Selected option for question: {} using JavaScript click", questionTitle);
        return true;
      } catch (Exception e) {
        log.debug("JavaScript click failed: {}", e.getMessage());
      }

      // Strategy 3: Try regular click with explicit wait
      try {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(5));
        wait.until(ExpectedConditions.elementToBeClickable(targetOption));
        targetOption.click();
        log.info("Selected option for question: {} using regular click", questionTitle);
        return true;
      } catch (Exception e) {
        log.debug("Regular click failed: {}", e.getMessage());
      }

      // Strategy 4: Try using Enter key on the option
      try {
        targetOption.sendKeys(Keys.ENTER);
        log.info("Selected option for question: {} using Enter key", questionTitle);
        return true;
      } catch (Exception e) {
        log.debug("Enter key failed: {}", e.getMessage());
      }

      // Strategy 5: Try using Space key on the option
      try {
        targetOption.sendKeys(Keys.SPACE);
        log.info("Selected option for question: {} using Space key", questionTitle);
        return true;
      } catch (Exception e) {
        log.debug("Space key failed: {}", e.getMessage());
      }

      log.warn("All click strategies failed for question: {}", questionTitle);
      return false;

    } catch (Exception e) {
      log.debug("Error in clickOptionWithMultipleStrategies for {}: {}", questionTitle,
          e.getMessage());
      return false;
    }
  }

  /**
   * Fill a single listbox using ComboboxHandler with retry logic
   */
  private boolean fillSingleListboxWithRetry(WebDriver driver, WebElement listboxElement,
      String questionTitle) {
    int maxRetries = 3;
    int retryCount = 0;

    while (retryCount < maxRetries) {
      try {
        log.debug("Attempting to fill listbox for {} (attempt {}/{})", questionTitle,
            retryCount + 1, maxRetries);

        boolean success = fillSingleListboxWithHandler(driver, listboxElement, questionTitle);

        if (success) {
          log.debug("Successfully filled listbox for {} on attempt {}", questionTitle,
              retryCount + 1);
          return true;
        }

        retryCount++;
        if (retryCount < maxRetries) {
          log.debug("Failed to fill listbox for {}, retrying in 1 second...", questionTitle);
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }

      } catch (Exception e) {
        log.debug("Exception filling listbox for {} (attempt {}): {}", questionTitle,
            retryCount + 1, e.getMessage());
        retryCount++;
        if (retryCount < maxRetries) {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }

    log.warn("Failed to fill listbox for {} after {} attempts", questionTitle, maxRetries);
    return false;
  }

  /**
   * Fill a single listbox using ComboboxHandler with fallback strategies
   */
  private boolean fillSingleListboxWithHandler(WebDriver driver, WebElement listboxElement,
      String questionTitle) {
    try {
      // Check if listbox is already filled
      if (isListboxAlreadyFilled(listboxElement)) {
        log.debug("Listbox already filled for: {}", questionTitle);
        return true;
      }

      // Strategy 1: Use ComboboxHandler with first available option
      String firstOptionText = getFirstAvailableOptionText(driver, listboxElement);
      if (firstOptionText != null && !firstOptionText.trim().isEmpty()) {
        log.debug("Attempting to fill listbox with first option: '{}'", firstOptionText);
        boolean success = comboboxHandler.fillComboboxQuestion(driver, listboxElement,
            questionTitle, firstOptionText, false);
        if (success) {
          return true;
        }
      } else {
        // If no options found, try to use a default option
        log.debug("No options found, trying with default option for: {}", questionTitle);
        String defaultOption = getDefaultOptionForQuestion(questionTitle);
        if (defaultOption != null) {
          boolean success = comboboxHandler.fillComboboxQuestion(driver, listboxElement,
              questionTitle, defaultOption, false);
          if (success) {
            return true;
          }
        }
      }

      // Strategy 2: Fallback to simple click approach if ComboboxHandler fails
      log.debug("ComboboxHandler failed, trying fallback approach for: {}", questionTitle);
      return fillListboxWithFallback(driver, listboxElement);

    } catch (Exception e) {
      log.debug("Error in fillSingleListboxWithHandler for {}: {}", questionTitle, e.getMessage());
      return false;
    }
  }

  /**
   * Generate unique identifier for a specific element
   */
  private String generateElementIdentifier(WebElement element, int index) {
    try {
      // Create a unique identifier based on element attributes and position
      String elementId = element.getAttribute("id");
      String elementClass = element.getAttribute("class");
      String elementRole = element.getAttribute("role");
      String elementAriaLabel = element.getAttribute("aria-label");

      // Combine attributes to create a unique identifier
      StringBuilder identifier = new StringBuilder();
      identifier.append("element_").append(index).append("_");

      if (elementId != null && !elementId.isEmpty()) {
        identifier.append("id_").append(elementId.hashCode()).append("_");
      }

      if (elementClass != null && !elementClass.isEmpty()) {
        identifier.append("class_").append(elementClass.hashCode()).append("_");
      }

      if (elementRole != null && !elementRole.isEmpty()) {
        identifier.append("role_").append(elementRole).append("_");
      }

      if (elementAriaLabel != null && !elementAriaLabel.isEmpty()) {
        identifier.append("aria_").append(elementAriaLabel.hashCode()).append("_");
      }

      // Add timestamp for additional uniqueness
      identifier.append("ts_").append(System.currentTimeMillis());

      return identifier.toString();
    } catch (Exception e) {
      // Fallback to simple identifier
      return "element_" + index + "_" + System.currentTimeMillis();
    }
  }

  /**
   * Get the preferred option text from the listbox, prioritizing tabindex="0"
   */
  private String getPreferredOptionText(WebDriver driver, WebElement listboxElement) {
    try {
      // Ensure dropdown open, then scope to the popup tied to this listbox
      if (!ensureDropdownExpanded(driver, listboxElement,
          getQuestionTitleForElement(listboxElement))) {
        return null;
      }

      WebElement popupContainer = findNearestVisiblePopup(driver, listboxElement);
      if (popupContainer == null) {
        log.debug("No popup container found for preferred option search");
        return null;
      }

      List<WebElement> options = popupContainer.findElements(By.cssSelector("[role='option']"));

      // Filter out placeholder options (empty data-value or "Chọn" text)
      List<WebElement> validOptions = new ArrayList<>();
      for (WebElement option : options) {
        if (option.isDisplayed()) {
          String dataValue = option.getAttribute("data-value");
          String optionText = getOptionText(option);

          // Skip placeholder options
          if (dataValue == null || dataValue.trim().isEmpty() || "chọn".equalsIgnoreCase(optionText)
              || "select".equalsIgnoreCase(optionText)) {
            log.debug("Skipping placeholder option: data-value='{}', text='{}'", dataValue,
                optionText);
            continue;
          }

          validOptions.add(option);
        }
      }

      log.debug("Found {} valid options (excluding placeholders)", validOptions.size());

      // First, try to find option with tabindex="0" (most preferred)
      for (WebElement option : validOptions) {
        String tabindex = option.getAttribute("tabindex");
        if ("0".equals(tabindex)) {
          String optionText = getOptionText(option);
          if (optionText != null && !optionText.trim().isEmpty()) {
            log.debug("Found preferred option with tabindex=0: '{}'", optionText);
            return optionText;
          }
        }
      }

      // If no tabindex=0 found, use the first valid option
      if (!validOptions.isEmpty()) {
        String optionText = getOptionText(validOptions.get(0));
        if (optionText != null && !optionText.trim().isEmpty()) {
          log.debug("Using first valid option: '{}'", optionText);
          return optionText;
        }
      }

      return null;

    } catch (Exception e) {
      log.debug("Error getting preferred option text: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Extract text from an option element
   */
  private String getOptionText(WebElement option) {
    try {
      // Try multiple strategies to get option text

      // Strategy 1: Check data-value attribute
      String dataValue = option.getAttribute("data-value");
      if (dataValue != null && !dataValue.trim().isEmpty()) {
        return dataValue.trim();
      }

      // Strategy 2: Check aria-label attribute
      String ariaLabel = option.getAttribute("aria-label");
      if (ariaLabel != null && !ariaLabel.trim().isEmpty()) {
        return ariaLabel.trim();
      }

      // Strategy 3: Check span with jsslot attribute
      try {
        WebElement span = option.findElement(By.cssSelector("span[jsslot]"));
        String spanText = span.getText();
        if (spanText != null && !spanText.trim().isEmpty()) {
          return spanText.trim();
        }
      } catch (Exception e) {
        // Span not found, continue
      }

      // Strategy 4: Get text content
      String textContent = option.getText();
      if (textContent != null && !textContent.trim().isEmpty()) {
        return textContent.trim();
      }

      return null;
    } catch (Exception e) {
      log.debug("Error extracting option text: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Get the first available option text from the listbox
   */
  private String getFirstAvailableOptionText(WebDriver driver, WebElement listboxElement) {
    try {
      // Step 1: Click to expand dropdown and ensure it's open
      try {
        listboxElement.click();
        Thread.sleep(800); // Longer wait for expansion

        // Verify dropdown is expanded
        String ariaExpanded = listboxElement.getAttribute("aria-expanded");
        if (!"true".equals(ariaExpanded)) {
          log.debug("Dropdown not expanded, trying JavaScript click");
          ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();",
              listboxElement);
          Thread.sleep(500);
        }
      } catch (Exception e) {
        log.debug("Failed to expand dropdown: {}", e.getMessage());
        return null;
      }

      // Step 2: Find nearest popup to this listbox and get options within it
      WebElement popupContainer = findNearestVisiblePopup(driver, listboxElement);
      if (popupContainer == null) {
        log.debug("No popup container found for first-available selection");
        return null;
      }
      List<WebElement> options = popupContainer.findElements(By.cssSelector("[role='option']"));

      // Step 3: Find the first valid option (excluding placeholders)
      WebElement firstClickableOption = null;

      // Filter out placeholder options first
      List<WebElement> validOptions = new ArrayList<>();
      for (WebElement option : options) {
        if (option.isDisplayed()) {
          String dataValue = option.getAttribute("data-value");
          String optionText = getOptionText(option);

          // Skip placeholder options
          if (dataValue == null || dataValue.trim().isEmpty() || "chọn".equalsIgnoreCase(optionText)
              || "select".equalsIgnoreCase(optionText)) {
            log.debug("Skipping placeholder option: data-value='{}', text='{}'", dataValue,
                optionText);
            continue;
          }

          validOptions.add(option);
        }
      }

      log.debug("Found {} valid options (excluding placeholders)", validOptions.size());

      // First, try to find option with tabindex="0"
      for (WebElement option : validOptions) {
        String tabindex = option.getAttribute("tabindex");
        if ("0".equals(tabindex)) {
          firstClickableOption = option;
          log.debug("Found valid option with tabindex=0");
          break;
        }
      }

      // If no tabindex="0" found, use the first valid option
      if (firstClickableOption == null && !validOptions.isEmpty()) {
        firstClickableOption = validOptions.get(0);
        log.debug("Using first valid option");
      }

      // Step 4: Get the option value
      if (firstClickableOption != null) {
        // Priority 1: data-value attribute
        String dataValue = firstClickableOption.getAttribute("data-value");
        if (dataValue != null && !dataValue.trim().isEmpty() && !"".equals(dataValue.trim())) {
          log.debug("Selected first option with data-value: '{}'", dataValue);
          return dataValue;
        }

        // Priority 2: text content
        String optionText = firstClickableOption.getText();
        if (optionText != null && !optionText.trim().isEmpty()) {
          log.debug("Selected first option with text: '{}'", optionText);
          return optionText;
        }

        // Priority 3: aria-label
        String ariaLabel = firstClickableOption.getAttribute("aria-label");
        if (ariaLabel != null && !ariaLabel.trim().isEmpty()) {
          log.debug("Selected first option with aria-label: '{}'", ariaLabel);
          return ariaLabel;
        }
      }

      // If no visible options found, try to get from data attributes
      List<String> dataAttributeOptions = new ArrayList<>();
      for (WebElement option : options) {
        try {
          String dataValue = option.getAttribute("data-value");
          if (dataValue != null && !dataValue.trim().isEmpty()) {
            dataAttributeOptions.add(dataValue);
          }

          String ariaLabel = option.getAttribute("aria-label");
          if (ariaLabel != null && !ariaLabel.trim().isEmpty()) {
            dataAttributeOptions.add(ariaLabel);
          }
        } catch (Exception e) {
          // Continue to next option
        }
      }

      // Choose a random option from data attributes (prefer 2nd, 3rd)
      if (!dataAttributeOptions.isEmpty()) {
        int optionIndex;
        if (dataAttributeOptions.size() >= 3) {
          // Prefer 2nd or 3rd option (index 1 or 2)
          optionIndex = new java.util.Random().nextInt(2) + 1; // 1 or 2
        } else if (dataAttributeOptions.size() >= 2) {
          // If only 2 options, choose 2nd option
          optionIndex = 1;
        } else {
          // If only 1 option, use it
          optionIndex = 0;
        }

        String selectedOption = dataAttributeOptions.get(optionIndex);
        log.debug("Selected option from data attributes {} (index {}): '{}'", optionIndex + 1,
            optionIndex, selectedOption);
        return selectedOption;
      }

      // If still no options found, try to look for options in the listbox element itself
      try {
        List<WebElement> inlineOptions =
            listboxElement.findElements(By.cssSelector("[role='option']"));
        List<String> inlineOptionTexts = new ArrayList<>();
        for (WebElement option : inlineOptions) {
          if (option.isDisplayed()) {
            String optionText = option.getText();
            if (optionText != null && !optionText.trim().isEmpty()) {
              inlineOptionTexts.add(optionText);
            }
          }
        }

        // Choose the first inline option (index 0)
        if (!inlineOptionTexts.isEmpty()) {
          int optionIndex = 0;
          String selectedOption = inlineOptionTexts.get(optionIndex);
          log.debug("Selected first inline option: '{}'", selectedOption);
          return selectedOption;
        }
      } catch (Exception e) {
        log.debug("Error looking for inline options: {}", e.getMessage());
      }

      // If no options found at all, try to get placeholder or default text
      try {
        String placeholder = listboxElement.getAttribute("placeholder");
        if (placeholder != null && !placeholder.trim().isEmpty()) {
          log.debug("Using placeholder as option: '{}'", placeholder);
          return placeholder;
        }

        String ariaLabel = listboxElement.getAttribute("aria-label");
        if (ariaLabel != null && !ariaLabel.trim().isEmpty()) {
          log.debug("Using aria-label as option: '{}'", ariaLabel);
          return ariaLabel;
        }
      } catch (Exception e) {
        log.debug("Error getting placeholder/aria-label: {}", e.getMessage());
      }

    } catch (Exception e) {
      log.debug("Error getting first option text: {}", e.getMessage());
    }

    log.warn("No options found in listbox");
    return null;
  }

  /**
   * Fallback method for filling listbox when ComboboxHandler fails
   */
  private boolean fillListboxWithFallback(WebDriver driver, WebElement listboxElement) {
    try {
      // Step 1: Click to expand dropdown and ensure it's open
      try {
        listboxElement.click();
        Thread.sleep(800); // Longer wait for expansion

        // Verify dropdown is expanded
        String ariaExpanded = listboxElement.getAttribute("aria-expanded");
        if (!"true".equals(ariaExpanded)) {
          log.debug("Dropdown not expanded, trying JavaScript click");
          ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();",
              listboxElement);
          Thread.sleep(500);
        }
      } catch (Exception e) {
        log.debug("Failed to expand dropdown: {}", e.getMessage());
        return false;
      }

      // Step 2: Scope to nearest popup and get options
      WebElement popupContainer = findNearestVisiblePopup(driver, listboxElement);
      if (popupContainer == null) {
        log.debug("No popup container found for fallback selection");
        return false;
      }
      List<WebElement> options = popupContainer.findElements(By.cssSelector("[role='option']"));

      // Step 3: Find the first valid option (excluding placeholders)
      WebElement selectedOption = null;

      // Filter out placeholder options first
      List<WebElement> validOptions = new ArrayList<>();
      for (WebElement option : options) {
        if (option.isDisplayed()) {
          String dataValue = option.getAttribute("data-value");
          String optionText = getOptionText(option);

          // Skip placeholder options
          if (dataValue == null || dataValue.trim().isEmpty() || "chọn".equalsIgnoreCase(optionText)
              || "select".equalsIgnoreCase(optionText)) {
            log.debug("Fallback: skipping placeholder option: data-value='{}', text='{}'",
                dataValue, optionText);
            continue;
          }

          validOptions.add(option);
        }
      }

      log.debug("Fallback: found {} valid options (excluding placeholders)", validOptions.size());

      // First, try to find option with tabindex="0"
      for (WebElement option : validOptions) {
        String tabindex = option.getAttribute("tabindex");
        if ("0".equals(tabindex)) {
          selectedOption = option;
          log.debug("Fallback: found valid option with tabindex=0");
          break;
        }
      }

      // If no tabindex="0" found, use the first valid option
      if (selectedOption == null && !validOptions.isEmpty()) {
        selectedOption = validOptions.get(0);
        log.debug("Fallback: using first valid option");
      }

      // Step 4: Click the selected option
      if (selectedOption != null) {
        log.debug("Fallback: trying to click selected option");

        try {
          // Try multiple click strategies
          try {
            selectedOption.click();
            log.debug("Successfully clicked option with regular click");
            return true;
          } catch (Exception e1) {
            log.debug("Regular click failed, trying JavaScript click");
            try {
              ((org.openqa.selenium.JavascriptExecutor) driver)
                  .executeScript("arguments[0].click();", selectedOption);
              log.debug("Successfully clicked option with JavaScript click");
              return true;
            } catch (Exception e2) {
              log.debug("JavaScript click failed, trying Enter key");
              selectedOption.sendKeys(Keys.ENTER);
              log.debug("Successfully used Enter key on option");
              return true;
            }
          }
        } catch (Exception e) {
          log.debug("All click strategies failed for selected option: {}", e.getMessage());
        }
      }

      // If no option was clicked, try Enter key on the listbox itself
      try {
        listboxElement.sendKeys(Keys.ENTER);
        log.debug("Used Enter key on listbox as fallback");
        return true;
      } catch (Exception e) {
        log.debug("Enter key fallback failed: {}", e.getMessage());
      }

    } catch (Exception e) {
      log.debug("Fallback listbox filling failed: {}", e.getMessage());
    }

    return false;
  }

  /**
   * Force-click any available option as a last resort
   */
  private boolean forceClickAnyOption(WebDriver driver, WebElement listboxElement,
      String questionTitle) {
    try {
      log.info("Force-clicking any available option for: {}", questionTitle);

      // Step 1: Click to expand dropdown and ensure it's open
      try {
        listboxElement.click();
        Thread.sleep(800); // Longer wait for expansion

        // Verify dropdown is expanded
        String ariaExpanded = listboxElement.getAttribute("aria-expanded");
        if (!"true".equals(ariaExpanded)) {
          log.debug("Dropdown not expanded, trying JavaScript click");
          ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();",
              listboxElement);
          Thread.sleep(500);
        }
        log.debug("Expanded dropdown for force-click approach");
      } catch (Exception e) {
        log.debug("Failed to expand dropdown for force-click: {}", e.getMessage());
        return false;
      }

      // Step 2: Find options within the nearest popup to avoid cross-listbox clicks
      List<WebElement> allOptions = new ArrayList<>();
      WebElement popupContainer = findNearestVisiblePopup(driver, listboxElement);
      if (popupContainer != null) {
        try {
          List<WebElement> options = popupContainer.findElements(By.cssSelector("[role='option']"));
          for (WebElement option : options) {
            if (option.isDisplayed() && option.isEnabled()) {
              allOptions.add(option);
            }
          }
          log.debug("Found {} options in nearest popup", allOptions.size());
        } catch (Exception e) {
          log.debug("Failed to read options from nearest popup: {}", e.getMessage());
        }
      }

      if (allOptions.isEmpty()) {
        log.warn("No options found with any selector for: {}", questionTitle);
        return false;
      }

      // Step 3: Filter out placeholder options and prioritize tabindex="0"
      List<WebElement> prioritizedOptions = new ArrayList<>();

      // Filter out placeholder options first
      for (WebElement option : allOptions) {
        if (option.isDisplayed() && option.isEnabled()) {
          String dataValue = option.getAttribute("data-value");
          String optionText = getOptionText(option);

          // Skip placeholder options
          if (dataValue == null || dataValue.trim().isEmpty() || "chọn".equalsIgnoreCase(optionText)
              || "select".equalsIgnoreCase(optionText)) {
            log.debug("Force-click: skipping placeholder option: data-value='{}', text='{}'",
                dataValue, optionText);
            continue;
          }

          prioritizedOptions.add(option);
        }
      }

      log.debug("Force-click: found {} valid options (excluding placeholders)",
          prioritizedOptions.size());

      // If no valid options found, try with all options as fallback
      if (prioritizedOptions.isEmpty()) {
        log.debug("Force-click: no valid options found, using all options as fallback");
        prioritizedOptions = allOptions;
      }

      // Try to click the first few prioritized options
      for (int i = 0; i < Math.min(3, prioritizedOptions.size()); i++) {
        WebElement option = prioritizedOptions.get(i);
        try {
          String optionText = option.getText();
          String tabindex = option.getAttribute("tabindex");
          log.info("Force-clicking option {}: '{}' (tabindex={}) for question: {}", i + 1,
              optionText, tabindex, questionTitle);

          // Multiple click strategies
          boolean clicked = false;

          // Strategy 1: JavaScript click
          try {
            ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();",
                option);
            clicked = true;
            log.info("Successfully force-clicked option using JavaScript for: {}", questionTitle);
          } catch (Exception e) {
            log.debug("JavaScript click failed: {}", e.getMessage());
          }

          // Strategy 2: Regular click if JS failed
          if (!clicked) {
            try {
              option.click();
              clicked = true;
              log.info("Successfully force-clicked option using regular click for: {}",
                  questionTitle);
            } catch (Exception e) {
              log.debug("Regular click failed: {}", e.getMessage());
            }
          }

          // Strategy 3: Send Enter key
          if (!clicked) {
            try {
              option.sendKeys(Keys.ENTER);
              clicked = true;
              log.info("Successfully force-clicked option using Enter key for: {}", questionTitle);
            } catch (Exception e) {
              log.debug("Enter key failed: {}", e.getMessage());
            }
          }

          if (clicked) {
            // Wait for selection to register
            Thread.sleep(500);

            // Verify if listbox is now filled
            if (isListboxAlreadyFilled(listboxElement)) {
              log.info("Force-click successful, listbox is now filled for: {}", questionTitle);
              return true;
            } else {
              log.debug("Option clicked but listbox not filled, trying next option");
            }
          }

        } catch (Exception e) {
          log.debug("Failed to force-click option {}: {}", i + 1, e.getMessage());
        }
      }

      log.warn("All force-click attempts failed for: {}", questionTitle);
      return false;

    } catch (Exception e) {
      log.error("Error in forceClickAnyOption for {}: {}", questionTitle, e.getMessage());
      return false;
    }
  }

  /**
   * Get a default option based on question title
   */
  private String getDefaultOptionForQuestion(String questionTitle) {
    if (questionTitle == null) {
      return "Option 1";
    }

    String lowerTitle = questionTitle.toLowerCase();

    // Common patterns for different question types
    if (lowerTitle.contains("gender") || lowerTitle.contains("giới tính")) {
      return "Nam";
    } else if (lowerTitle.contains("age") || lowerTitle.contains("tuổi")) {
      return "18-25";
    } else if (lowerTitle.contains("education") || lowerTitle.contains("học vấn")) {
      return "Đại học";
    } else if (lowerTitle.contains("occupation") || lowerTitle.contains("nghề nghiệp")) {
      return "Nhân viên";
    } else if (lowerTitle.contains("location") || lowerTitle.contains("địa điểm")) {
      return "Hà Nội";
    } else if (lowerTitle.contains("city") || lowerTitle.contains("thành phố")) {
      return "Hà Nội";
    } else if (lowerTitle.contains("province") || lowerTitle.contains("tỉnh")) {
      return "Hà Nội";
    } else if (lowerTitle.contains("country") || lowerTitle.contains("quốc gia")) {
      return "Việt Nam";
    } else if (lowerTitle.contains("language") || lowerTitle.contains("ngôn ngữ")) {
      return "Tiếng Việt";
    } else if (lowerTitle.contains("preference") || lowerTitle.contains("sở thích")) {
      return "Có";
    } else if (lowerTitle.contains("yes") || lowerTitle.contains("có")) {
      return "Có";
    } else if (lowerTitle.contains("no") || lowerTitle.contains("không")) {
      return "Không";
    } else if (lowerTitle.contains("agree") || lowerTitle.contains("đồng ý")) {
      return "Đồng ý";
    } else if (lowerTitle.contains("disagree") || lowerTitle.contains("không đồng ý")) {
      return "Không đồng ý";
    } else if (lowerTitle.contains("satisfied") || lowerTitle.contains("hài lòng")) {
      return "Hài lòng";
    } else if (lowerTitle.contains("frequency") || lowerTitle.contains("tần suất")) {
      return "Thỉnh thoảng";
    } else if (lowerTitle.contains("time") || lowerTitle.contains("thời gian")) {
      return "Sáng";
    } else if (lowerTitle.contains("day") || lowerTitle.contains("ngày")) {
      return "Thứ 2";
    } else if (lowerTitle.contains("month") || lowerTitle.contains("tháng")) {
      return "Tháng 1";
    } else if (lowerTitle.contains("year") || lowerTitle.contains("năm")) {
      return "2024";
    }

    // Default options for common question types
    return "Option 1";
  }

  /**
   * Check if listbox is already filled with a selection
   */
  private boolean isListboxAlreadyFilled(WebElement listboxElement) {
    try {
      // Preferred: any option marked as selected
      List<WebElement> selectedOptions =
          listboxElement.findElements(By.cssSelector("[role='option'][aria-selected='true']"));

      if (!selectedOptions.isEmpty()) {
        WebElement opt = selectedOptions.get(0);
        String selectedText = opt.getText() == null ? "" : opt.getText().trim();
        String tabindex = opt.getAttribute("tabindex");
        String dataValue = opt.getAttribute("data-value");
        String normalized = normalizeVietnamese(selectedText);
        log.debug("Listbox aria-selected text: '{}', tabindex: {}, data-value: {}", selectedText,
            tabindex, dataValue);

        // If data-value is empty OR text equals placeholder ("chọn", "select") => not filled
        if (dataValue == null || dataValue.trim().isEmpty() || "chọn".equalsIgnoreCase(selectedText)
            || "select".equalsIgnoreCase(selectedText) || "chon".equals(normalized)) {
          log.debug("Listbox has placeholder selected, considering as not filled");
          return false;
        }

        log.debug("Listbox has valid selection: '{}'", selectedText);
        return true;
      }

      // Alternative: currently focused/selected option via tabindex="0"
      List<WebElement> selectedByTabindex =
          listboxElement.findElements(By.cssSelector("[role='option'][tabindex='0']"));

      if (!selectedByTabindex.isEmpty()) {
        WebElement opt = selectedByTabindex.get(0);
        String selectedText = opt.getText() == null ? "" : opt.getText().trim();
        String dataValue = opt.getAttribute("data-value");
        String normalized = normalizeVietnamese(selectedText);
        log.debug("Listbox tabindex=0 text: '{}', data-value: {}", selectedText, dataValue);

        if (dataValue == null || dataValue.trim().isEmpty() || "chọn".equalsIgnoreCase(selectedText)
            || "select".equalsIgnoreCase(selectedText) || "chon".equals(normalized)) {
          log.debug("Listbox tabindex=0 has placeholder, considering as not filled");
          return false;
        }

        log.debug("Listbox tabindex=0 has valid selection: '{}'", selectedText);
        return true;
      }

      // Fallback: try to detect any valid chosen option among options
      List<WebElement> allOptions = listboxElement.findElements(By.cssSelector("[role='option']"));
      for (WebElement option : allOptions) {
        String dataValue = option.getAttribute("data-value");
        String ariaSelected = option.getAttribute("aria-selected");
        String text = option.getText() == null ? "" : option.getText().trim();
        String normalized = normalizeVietnamese(text);
        if ("true".equals(ariaSelected)) {
          if (dataValue != null && !dataValue.trim().isEmpty() && !"chọn".equalsIgnoreCase(text)
              && !"select".equalsIgnoreCase(text) && !"chon".equals(normalized)) {
            log.debug("Listbox has valid selected option: '{}'", text);
            return true;
          } else {
            log.debug("Listbox has placeholder selected: '{}'", text);
            return false;
          }
        }
      }

      log.debug("Listbox has no valid selection");
      return false;
    } catch (Exception e) {
      log.debug("Error checking if listbox is filled: {}", e.getMessage());
      return false;
    }
  }

  private String normalizeVietnamese(String input) {
    if (input == null) {
      return "";
    }
    String lower = input.toLowerCase();
    String normalized = Normalizer.normalize(lower, Normalizer.Form.NFD)
        .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    // Also strip any remaining special whitespace
    return normalized.trim();
  }

  /**
   * Get question title for better logging
   */
  private String getQuestionTitleForElement(WebElement listboxElement) {
    try {
      // Try to find the question title in the parent elements
      WebElement parent = listboxElement.findElement(By.xpath(
          "./ancestor::div[contains(@class, 'freebirdFormviewerViewItemsItemItem') or contains(@class, 'freebirdFormviewerViewItemsItem')]"));
      if (parent != null) {
        // Look for title elements
        List<WebElement> titleElements = parent.findElements(
            By.cssSelector("[role='heading'], .freebirdFormviewerViewItemsItemItemTitle"));
        for (WebElement titleElement : titleElements) {
          String title = titleElement.getText();
          if (title != null && !title.trim().isEmpty()) {
            return title.trim();
          }
        }
      }
    } catch (Exception e) {
      // Ignore errors in title extraction
    }

    return "Unknown Question";
  }

  private int tryClickFirstInEachRow(WebDriver driver, By optionLocator) {
    try {
      // For grid questions, we need to click one option per row
      // Find all radio groups (which represent rows in grids)
      List<WebElement> radioGroups = driver.findElements(By.cssSelector("[role='radiogroup']"));
      int clickedRows = 0;

      for (WebElement group : radioGroups) {
        if (group.isDisplayed()) {
          // Find the first option in this row
          List<WebElement> options = group.findElements(optionLocator);
          for (WebElement option : options) {
            if (option.isDisplayed() && option.isEnabled()) {
              try {
                option.click();
                clickedRows++;
                log.debug("Clicked first option in grid row");
                break; // Only click one per row
              } catch (Exception e) {
                log.debug("Failed to click grid option: {}", e.getMessage());
              }
            }
          }
        }
      }

      if (clickedRows > 0) {
        log.info("Clicked first option in {} grid rows", clickedRows);
      }

      return clickedRows;
    } catch (Exception e) {
      log.debug("Error clicking grid options: {}", e.getMessage());
      return 0;
    }
  }
}


