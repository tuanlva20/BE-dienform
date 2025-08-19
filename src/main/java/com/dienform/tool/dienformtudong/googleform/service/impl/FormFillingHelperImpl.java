package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.googleform.handler.ComboboxHandler;
import com.dienform.tool.dienformtudong.googleform.handler.GridQuestionHandler;
import com.dienform.tool.dienformtudong.googleform.service.FormFillingHelper;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of FormFillingHelper that reuses logic from GoogleFormServiceImpl
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FormFillingHelperImpl implements FormFillingHelper {

  // Cache for form locators (reused from GoogleFormServiceImpl)
  private final Map<UUID, Map<UUID, By>> formLocatorCache = new ConcurrentHashMap<>();
  private final ComboboxHandler comboboxHandler;
  private final GridQuestionHandler gridQuestionHandler;

  @Override
  public WebElement resolveQuestionElement(WebDriver driver, String formUrl, Question question) {
    log.info("Attempting to resolve question element for: '{}' (type: {})", question.getTitle(),
        question.getType());

    UUID formId = question.getForm() != null ? question.getForm().getId() : null;
    if (formId == null) {
      log.warn("Form ID is null for question: {}", question.getTitle());
    }

    // Try multiple strategies to find the question element
    WebElement element = null;
    Map<String, String> additionalData = question.getAdditionalData();

    // Strategy 1: Try with section index and title (highest priority)
    if (additionalData != null) {
      String sectionIndex = additionalData.get("section_index");
      String questionTitle = question.getTitle();

      if (sectionIndex != null && questionTitle != null) {
        try {
          log.info("Trying section-based search: section={}, title='{}'", sectionIndex,
              questionTitle);

          // Try to find question by title within the current section context
          By sectionBasedLocator =
              By.xpath("//div[@role='listitem'][contains(., '" + questionTitle.trim() + "')]");

          element = new WebDriverWait(driver, Duration.ofSeconds(10))
              .until(ExpectedConditions.presenceOfElementLocated(sectionBasedLocator));
          log.info("Found question element using section-based search for: {} (section: {})",
              question.getTitle(), sectionIndex);
          return element;
        } catch (Exception e) {
          log.debug("Section-based search failed for question: {} (section: {})",
              question.getTitle(), sectionIndex);
        }
      }
    }

    // Strategy 2: Try with cached locator
    if (formId != null) {
      Map<UUID, By> perForm =
          formLocatorCache.computeIfAbsent(formId, k -> new ConcurrentHashMap<>());
      By by = perForm.computeIfAbsent(question.getId(), k -> buildLocatorForQuestion(question));

      try {
        log.debug("Trying cached locator: {}", by);
        element = new WebDriverWait(driver, Duration.ofSeconds(10))
            .until(ExpectedConditions.presenceOfElementLocated(by));
        log.info("Found question element using cached locator for: {}", question.getTitle());
        return element;
      } catch (Exception e) {
        log.debug("Cached locator failed for question: {}", question.getTitle());
      }
    }

    // Strategy 2: Try with fresh locator
    By freshLocator = buildLocatorForQuestion(question);
    try {
      log.debug("Trying fresh locator: {}", freshLocator);
      element = new WebDriverWait(driver, Duration.ofSeconds(10))
          .until(ExpectedConditions.presenceOfElementLocated(freshLocator));
      log.info("Found question element using fresh locator for: {}", question.getTitle());
      return element;
    } catch (Exception e) {
      log.debug("Fresh locator failed for question: {}", question.getTitle());
    }

    // Strategy 3: Try enhanced text-based search with section context
    try {
      log.debug("Trying enhanced text-based search for question: {}", question.getTitle());
      String questionTitle = question.getTitle();
      if (questionTitle != null && !questionTitle.trim().isEmpty()) {

        // Try multiple text-based approaches
        By[] textLocators =
            {By.xpath("//div[@role='listitem'][contains(., '" + questionTitle.trim() + "')]"),
                By.xpath("//div[@role='heading'][contains(text(), '" + questionTitle.trim()
                    + "')]/ancestor::div[@role='listitem']"),
                By.xpath(
                    "//div[contains(text(), '" + questionTitle.trim() + "') and @role='listitem']"),
                By.xpath("//div[@role='listitem'][.//div[contains(text(), '" + questionTitle.trim()
                    + "')]]")};

        for (By textLocator : textLocators) {
          try {
            element = new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(ExpectedConditions.presenceOfElementLocated(textLocator));
            log.info("Found question element using enhanced text-based search for: {}",
                question.getTitle());
            return element;
          } catch (Exception e) {
            log.debug("Text locator {} failed: {}", textLocator, e.getMessage());
          }
        }
      }
    } catch (Exception e) {
      log.debug("Enhanced text-based search failed for question: {}", question.getTitle());
    }

    // Strategy 4: Try to find by position (if available)
    if (additionalData != null) {
      String position = additionalData.get("position");
      if (position != null) {
        try {
          int pos = Integer.parseInt(position);
          By positionLocator = By.xpath("(//div[@role='listitem'])[" + pos + "]");
          log.debug("Trying position-based locator: {}", positionLocator);
          element = new WebDriverWait(driver, Duration.ofSeconds(5))
              .until(ExpectedConditions.presenceOfElementLocated(positionLocator));
          log.info("Found question element using position-based search for: {}",
              question.getTitle());
          return element;
        } catch (Exception e) {
          log.debug("Position-based search failed for question: {}", question.getTitle());
        }
      }
    }

    // Strategy 5: Try to find by liIndex (if available)
    if (additionalData != null) {
      String liIndex = additionalData.get("liIndex");
      if (liIndex != null) {
        try {
          int index = Integer.parseInt(liIndex);
          By liIndexLocator = By.xpath("(//div[@role='listitem'])[" + (index + 1) + "]");
          log.debug("Trying liIndex-based locator: {}", liIndexLocator);
          element = new WebDriverWait(driver, Duration.ofSeconds(5))
              .until(ExpectedConditions.presenceOfElementLocated(liIndexLocator));
          log.info("Found question element using liIndex-based search for: {}",
              question.getTitle());
          return element;
        } catch (Exception e) {
          log.debug("liIndex-based search failed for question: {}", question.getTitle());
        }
      }
    }

    log.error("Failed to resolve question element for question: {} after trying all strategies",
        question.getTitle());
    return null;
  }

  @Override
  public boolean fillQuestionByType(WebDriver driver, WebElement questionElement, Question question,
      QuestionOption option, boolean humanLike) {
    try {
      // Check if WebDriver is still active
      if (driver == null || isWebDriverQuit(driver)) {
        log.error("WebDriver is null or has been quit, cannot fill question: {}",
            question.getTitle());
        return false;
      }

      log.info("Processing question: '{}' (type: {}) with option: '{}'", question.getTitle(),
          question.getType(), option.getText());

      boolean success = false;
      switch (question.getType().toLowerCase()) {
        case "radio":
          success = fillRadioQuestion(driver, questionElement, question, option, humanLike);
          break;
        case "checkbox":
          success = fillCheckboxQuestion(driver, questionElement, question, option, humanLike);
          break;
        case "text":
        case "email":
        case "textarea":
        case "short_answer":
        case "paragraph":
          success = fillTextQuestion(driver, questionElement, question.getTitle(),
              Map.of(question, option), humanLike);
          break;
        case "combobox":
        case "select":
          // Delegate to ComboboxHandler for robust selection
          success = comboboxHandler.fillComboboxQuestion(driver, questionElement,
              question.getTitle(), option.getText(), humanLike);
          break;
        case "multiple_choice_grid":
          // Delegate to GridQuestionHandler
          gridQuestionHandler.fillMultipleChoiceGridQuestion(driver, questionElement, question,
              option, humanLike);
          success = true;
          break;
        case "checkbox_grid":
          gridQuestionHandler.fillCheckboxGridQuestion(driver, questionElement, question, option,
              humanLike);
          success = true;
          break;
        default:
          log.warn("Unsupported question type: {}", question.getType());
          return false;
      }

      if (success) {
        log.info("Successfully filled question: {}", question.getTitle());
      } else {
        log.warn("Failed to fill question: {}", question.getTitle());
      }

      return success;
    } catch (Exception e) {
      log.error("Error filling question {}: {}", question.getTitle(), e.getMessage(), e);
      return false;
    }
  }

  /**
   * Build a stable By locator for a question using saved additionalData
   */
  private By buildLocatorForQuestion(Question question) {
    Map<String, String> add = question.getAdditionalData();
    if (add != null) {
      // Priority 1: Use section_index and title combination
      String sectionIndex = add.get("section_index");
      String questionTitle = question.getTitle();
      if (sectionIndex != null && questionTitle != null) {
        log.debug("Building locator using section_index={} and title='{}'", sectionIndex,
            questionTitle);
        return By.xpath("//div[@role='listitem'][contains(., '" + questionTitle.trim() + "')]");
      }

      // Priority 2: Use liIndex
      String li = add.get("liIndex");
      if (li != null) {
        try {
          int i = Integer.parseInt(li);
          log.debug("Building locator using liIndex={}", i);
          return By.xpath("(//div[@role='listitem'])[" + (i + 1) + "]");
        } catch (NumberFormatException ignore) {
        }
      }

      // Priority 3: Use containerXPath
      String x = add.get("containerXPath");
      if (x != null && !x.isBlank()) {
        log.debug("Building locator using containerXPath={}", x);
        return By.xpath(x);
      }

      // Priority 4: Use headingNormalized
      String t = add.get("headingNormalized");
      if (t != null && !t.isBlank()) {
        log.debug("Building locator using headingNormalized={}", t);
        return By.xpath("//div[@role='listitem'][.//div[@role='heading' and normalize-space()=\""
            + t.replace("\"", "\\\"") + "\"]]");
      }
    }

    // Fallback: Use question title
    String t = question.getTitle() == null ? "" : question.getTitle();
    log.debug("Building fallback locator using title='{}'", t);
    return By.xpath("//div[@role='listitem'][.//div[@role='heading' and normalize-space()=\""
        + t.replace("\"", "\\\"") + "\"]]");
  }

  /**
   * Check if WebDriver is quit
   */
  private boolean isWebDriverQuit(WebDriver driver) {
    try {
      driver.getCurrentUrl();
      return false;
    } catch (Exception e) {
      return true;
    }
  }

  // Simplified question filling methods - basic implementation
  private boolean fillRadioQuestion(WebDriver driver, WebElement questionElement, Question question,
      QuestionOption option, boolean humanLike) {
    try {
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
      String optionText = option.getText() == null ? "" : option.getText();
      log.info("Looking for radio options with text: '{}'", optionText);

      List<WebElement> radioOptions =
          questionElement.findElements(By.cssSelector("[role='radio']"));

      log.info("Found {} radio options", radioOptions.size());

      // Strategy 1: Try by data-value attribute
      for (WebElement radio : radioOptions) {
        try {
          String dataValue = radio.getAttribute("data-value");
          log.debug("Radio option data-value: '{}'", dataValue);
          if (dataValue != null && dataValue.trim().equals(optionText.trim())) {
            wait.until(ExpectedConditions.elementToBeClickable(radio));
            radio.click();
            log.info("Selected radio option by data-value: {}", optionText);
            return true;
          }
        } catch (Exception e) {
          log.debug("Error checking radio data-value: {}", e.getMessage());
        }
      }

      // Strategy 2: Try by aria-label attribute
      for (WebElement radio : radioOptions) {
        try {
          String ariaLabel = radio.getAttribute("aria-label");
          log.debug("Radio option aria-label: '{}'", ariaLabel);
          if (ariaLabel != null && ariaLabel.trim().equals(optionText.trim())) {
            wait.until(ExpectedConditions.elementToBeClickable(radio));
            radio.click();
            log.info("Selected radio option by aria-label: {}", optionText);
            return true;
          }
        } catch (Exception e) {
          log.debug("Error checking radio aria-label: {}", e.getMessage());
        }
      }

      // Strategy 3: Try by text content (contains)
      for (WebElement radio : radioOptions) {
        try {
          String text = radio.getText();
          log.debug("Radio option text: '{}'", text);
          if (text != null && text.trim().contains(optionText.trim())) {
            wait.until(ExpectedConditions.elementToBeClickable(radio));
            radio.click();
            log.info("Selected radio option by text content: {}", optionText);
            return true;
          }
        } catch (Exception e) {
          log.debug("Error checking radio text: {}", e.getMessage());
        }
      }

      // Strategy 4: Try by partial match
      for (WebElement radio : radioOptions) {
        try {
          String dataValue = radio.getAttribute("data-value");
          String ariaLabel = radio.getAttribute("aria-label");
          String text = radio.getText();

          if ((dataValue != null && dataValue.toLowerCase().contains(optionText.toLowerCase()))
              || (ariaLabel != null && ariaLabel.toLowerCase().contains(optionText.toLowerCase()))
              || (text != null && text.toLowerCase().contains(optionText.toLowerCase()))) {
            wait.until(ExpectedConditions.elementToBeClickable(radio));
            radio.click();
            log.info("Selected radio option by partial match: {}", optionText);
            return true;
          }
        } catch (Exception e) {
          log.debug("Error checking radio partial match: {}", e.getMessage());
        }
      }

      // 'Other' handling for radio
      try {
        for (WebElement radio : radioOptions) {
          String dataValue = radio.getAttribute("data-value");
          if ("__other_option__".equalsIgnoreCase(dataValue)) {
            wait.until(ExpectedConditions.elementToBeClickable(radio));
            radio.click();
            log.info("Selected radio __other_option__ for '{}'", optionText);
            fillOtherIfPresent(driver, questionElement, option, humanLike);
            return true;
          }
        }
      } catch (Exception ignore) {
      }

      log.warn("Could not find radio option: '{}' among {} options", optionText,
          radioOptions.size());
      return false;
    } catch (Exception e) {
      log.error("Error filling radio question: {}", e.getMessage());
      return false;
    }
  }

  private boolean fillCheckboxQuestion(WebDriver driver, WebElement questionElement,
      Question question, QuestionOption option, boolean humanLike) {
    try {
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
      List<WebElement> checkboxOptions =
          questionElement.findElements(By.cssSelector("[role='checkbox']"));

      String raw = option.getText() == null ? "" : option.getText();
      String[] tokens = raw.split("[,|]");
      boolean any = false;
      for (String tk : tokens) {
        String token = tk.trim();
        if (token.isEmpty())
          continue;

        boolean matched = false;
        // data-answer-value
        for (WebElement checkbox : checkboxOptions) {
          try {
            String dataAnswerValue = checkbox.getAttribute("data-answer-value");
            if (dataAnswerValue != null
                && (dataAnswerValue.equals(token) || dataAnswerValue.equalsIgnoreCase(token))) {
              wait.until(ExpectedConditions.elementToBeClickable(checkbox));
              checkbox.click();
              any = true;
              matched = true;
              if ("__other_option__".equalsIgnoreCase(dataAnswerValue)
                  || checkbox.getAttribute("data-other-checkbox") != null) {
                fillOtherIfPresent(driver, questionElement, option, humanLike);
              }
              break;
            }
          } catch (Exception ignore) {
          }
        }
        if (matched)
          continue;
        // aria-label
        for (WebElement checkbox : checkboxOptions) {
          try {
            String aria = checkbox.getAttribute("aria-label");
            if (aria != null && (aria.equals(token) || aria.equalsIgnoreCase(token))) {
              wait.until(ExpectedConditions.elementToBeClickable(checkbox));
              checkbox.click();
              any = true;
              matched = true;
              break;
            }
          } catch (Exception ignore) {
          }
        }
        if (matched)
          continue;
        // visible text
        for (WebElement checkbox : checkboxOptions) {
          try {
            String text = checkbox.getText() == null ? "" : checkbox.getText().trim();
            if (text.isEmpty()) {
              try {
                WebElement span =
                    checkbox.findElement(By.xpath(".//following-sibling::div//span[@dir='auto']"));
                text = span.getText().trim();
              } catch (Exception ignore) {
              }
            }
            if (!text.isEmpty() && (text.equals(token) || text.equalsIgnoreCase(token))) {
              wait.until(ExpectedConditions.elementToBeClickable(checkbox));
              checkbox.click();
              any = true;
              matched = true;
              break;
            }
          } catch (Exception ignore) {
          }
        }
      }
      if (!any)
        log.warn("Could not find checkbox option(s): {}", raw);
      return any;
    } catch (Exception e) {
      log.error("Error filling checkbox question: {}", e.getMessage());
      return false;
    }
  }

  private boolean fillTextQuestion(WebDriver driver, WebElement questionElement,
      String questionTitle, Map<Question, QuestionOption> selections, boolean humanLike) {
    try {
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
      // Relaxed: allow any visible input/textarea inside the question block
      WebElement input = null;
      List<WebElement> candidates = questionElement
          .findElements(By.cssSelector("input[type='text'], input[type='email'], textarea, input"));
      for (WebElement el : candidates) {
        try {
          if (el.isDisplayed() && el.isEnabled()) {
            input = el;
            break;
          }
        } catch (Exception ignore) {
        }
      }

      QuestionOption option = selections.values().iterator().next();
      if (option != null && option.getText() != null) {
        wait.until(ExpectedConditions.elementToBeClickable(input));
        try {
          input.clear();
        } catch (Exception ignore) {
        }
        input.sendKeys(option.getText());
        log.info("Filled text question: {}", option.getText());
        return true;
      }

      log.warn("No text option found for question: {}", questionTitle);
      return false;
    } catch (Exception e) {
      log.error("Error filling text question: {}", e.getMessage());
      return false;
    }
  }

  private void fillOtherIfPresent(WebDriver driver, WebElement questionElement,
      QuestionOption option, boolean humanLike) {
    try {
      List<WebElement> exact = questionElement
          .findElements(By.cssSelector("input[type='text'][aria-label='Câu trả lời khác']"));
      WebElement input = null;
      for (WebElement e : exact) {
        if (e.isDisplayed() && e.isEnabled()) {
          input = e;
          break;
        }
      }
      if (input == null) {
        List<WebElement> anyAria =
            questionElement.findElements(By.cssSelector("input[type='text'][aria-label]"));
        for (WebElement e : anyAria) {
          try {
            String aria = e.getAttribute("aria-label");
            if (aria != null && aria.toLowerCase().contains("khác") && e.isDisplayed()
                && e.isEnabled()) {
              input = e;
              break;
            }
          } catch (Exception ignore) {
          }
        }
      }
      if (input == null) {
        try {
          WebElement span = questionElement
              .findElement(By.xpath(".//span[@dir='auto' and normalize-space(.)='Mục khác:']"));
          WebElement container =
              span.findElement(By.xpath("ancestor::label/following-sibling::div"));
          input = container.findElement(By.xpath(".//input[@type='text']"));
        } catch (Exception ignore) {
        }
      }
      if (input == null)
        return;

      String sample = option != null && option.getText() != null ? option.getText() : null;
      if (sample != null && sample.contains("-")) {
        sample = sample.substring(sample.lastIndexOf('-') + 1).trim();
      }
      if (sample == null || sample.isEmpty() || sample.equalsIgnoreCase("__other_option__")) {
        String uuid = java.util.UUID.randomUUID().toString().replace("-", "");
        sample = "autogen_" + (uuid.length() >= 8 ? uuid.substring(0, 8) : uuid);
      }

      try {
        input.clear();
      } catch (Exception ignore) {
      }
      if (humanLike) {
        for (char c : sample.toCharArray()) {
          input.sendKeys(String.valueOf(c));
          try {
            Thread.sleep(40 + new java.util.Random().nextInt(60));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      } else {
        input.sendKeys(sample);
      }
      log.info("Filled 'Other' text with: {}", sample);
    } catch (Exception e) {
      log.debug("fillOtherIfPresent error: {}", e.getMessage());
    }
  }

  // Removed local combobox/grid fillers; use dedicated handlers for consistency
}
