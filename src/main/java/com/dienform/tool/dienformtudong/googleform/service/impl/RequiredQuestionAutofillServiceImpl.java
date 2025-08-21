package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.googleform.service.RequiredQuestionAutofillService;
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

      // Check if questions are already satisfied before filling
      if (areQuestionsAlreadySatisfied(driver)) {
        log.info("Questions are already satisfied in section {}, marking as autofilled",
            currentSectionId);
        filledSections.add(currentSectionId);
        return true;
      }

      // Text inputs - fill ALL visible text inputs
      fillAllVisible(driver, By.cssSelector("input[type='text']"), "N/A");
      fillAllVisible(driver, By.cssSelector("input[type='email']"), "test@example.com");
      fillAllVisible(driver, By.tagName("textarea"), "N/A");

      // Radio: pick first option for EACH radio group
      clickFirstInEachRadioGroup(driver);

      // Checkbox: tick first option for EACH checkbox group
      clickFirstInEachCheckboxGroup(driver);

      // Combobox/Listbox: open then select first option for EACH listbox
      fillAllListboxes(driver);

      // Multiple choice grid: for each row pick first radio
      tryClickFirstInEachRow(driver, By.cssSelector("[role='radio']"));

      // Checkbox grid: pick at least one checkbox per row
      tryClickFirstInEachRow(driver, By.cssSelector("[role='checkbox']"));

      // Mark this section as autofilled
      filledSections.add(currentSectionId);

      log.info("Completed satisfying required questions in section: {}", currentSectionId);
      return true;
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
   * Tạo unique identifier cho section hiện tại dựa trên URL và các element hiện có
   */
  private String getCurrentSectionIdentifier(WebDriver driver) {
    try {
      String currentUrl = driver.getCurrentUrl();

      // Lấy tất cả question titles trong section hiện tại
      List<WebElement> questionElements =
          driver.findElements(By.cssSelector("div[role='listitem']"));
      StringBuilder sectionContent = new StringBuilder();

      for (WebElement element : questionElements) {
        try {
          List<WebElement> titleElements = element.findElements(By.cssSelector("[role='heading']"));
          if (!titleElements.isEmpty()) {
            String title = titleElements.get(0).getText().trim();
            if (!title.isEmpty()) {
              sectionContent.append(title).append("|");
            }
          }
        } catch (Exception e) {
          // Ignore individual element errors
        }
      }

      // Tạo hash từ URL và content để tạo unique identifier
      String content = currentUrl + "|" + sectionContent.toString();
      return String.valueOf(content.hashCode());
    } catch (Exception e) {
      // Fallback to URL hash if content extraction fails
      return String.valueOf(driver.getCurrentUrl().hashCode());
    }
  }

  /**
   * Check if questions are already satisfied (have answers) to avoid unnecessary filling
   */
  private boolean areQuestionsAlreadySatisfied(WebDriver driver) {
    try {
      // Check if any text inputs have content
      List<WebElement> textInputs =
          driver.findElements(By.cssSelector("input[type='text'], input[type='email'], textarea"));
      boolean hasTextContent = false;
      int textInputCount = 0;
      for (WebElement input : textInputs) {
        if (input.isDisplayed() && input.isEnabled()) {
          textInputCount++;
          String value = input.getAttribute("value");
          if (value != null && !value.trim().isEmpty()) {
            hasTextContent = true;
            break;
          }
        }
      }

      // Check if any radio buttons are selected
      List<WebElement> radioGroups = driver.findElements(By.cssSelector("[role='radiogroup']"));
      boolean hasRadioSelected = false;
      int radioGroupCount = 0;
      for (WebElement group : radioGroups) {
        if (group.isDisplayed()) {
          radioGroupCount++;
          List<WebElement> radios = group.findElements(By.cssSelector("[role='radio']"));
          for (WebElement radio : radios) {
            if ("true".equals(radio.getAttribute("aria-checked"))) {
              hasRadioSelected = true;
              break;
            }
          }
          if (hasRadioSelected)
            break;
        }
      }

      // Check if any checkboxes are selected
      List<WebElement> checkboxes = driver.findElements(By.cssSelector("[role='checkbox']"));
      boolean hasCheckboxSelected = false;
      int checkboxCount = 0;
      for (WebElement checkbox : checkboxes) {
        if (checkbox.isDisplayed()) {
          checkboxCount++;
          if ("true".equals(checkbox.getAttribute("aria-checked"))) {
            hasCheckboxSelected = true;
            break;
          }
        }
      }

      // Check if any listboxes have selections
      List<WebElement> listboxes = driver.findElements(By.cssSelector("[role='listbox']"));
      boolean hasListboxSelection = false;
      int listboxCount = 0;
      for (WebElement listbox : listboxes) {
        if (listbox.isDisplayed()) {
          listboxCount++;
          // Check if listbox has a selected value (not empty)
          String selectedValue = listbox.getAttribute("aria-label");
          if (selectedValue != null && !selectedValue.trim().isEmpty()
              && !selectedValue.toLowerCase().contains("select")
              && !selectedValue.toLowerCase().contains("chọn")) {
            hasListboxSelection = true;
            break;
          }
        }
      }

      // Chỉ coi là satisfied nếu có ít nhất một loại câu hỏi và có câu trả lời
      boolean hasQuestions = (textInputCount > 0) || (radioGroupCount > 0) || (checkboxCount > 0)
          || (listboxCount > 0);
      boolean hasAnswers =
          hasTextContent || hasRadioSelected || hasCheckboxSelected || hasListboxSelection;

      boolean alreadySatisfied = hasQuestions && hasAnswers;

      log.info(
          "Questions already satisfied check: text={}({}), radio={}({}), checkbox={}({}), listbox={}({}), hasQuestions={}, hasAnswers={}, overall={}",
          hasTextContent, textInputCount, hasRadioSelected, radioGroupCount, hasCheckboxSelected,
          checkboxCount, hasListboxSelection, listboxCount, hasQuestions, hasAnswers,
          alreadySatisfied);

      return alreadySatisfied;
    } catch (Exception e) {
      log.debug("Error checking if questions are already satisfied: {}", e.getMessage());
      return false; // If we can't determine, assume not satisfied
    }
  }

  /**
   * Fill ALL visible elements matching the locator
   */
  private void fillAllVisible(WebDriver driver, By locator, String value) {
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
    } catch (Exception e) {
      log.debug("Error filling text inputs: {}", e.getMessage());
    }
  }

  /**
   * Click first option in EACH radio group (not just the first radio found)
   */
  private void clickFirstInEachRadioGroup(WebDriver driver) {
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
    } catch (Exception e) {
      log.debug("Error clicking radio groups: {}", e.getMessage());
    }
  }

  /**
   * Click first option in EACH checkbox group (not just the first checkbox found)
   */
  private void clickFirstInEachCheckboxGroup(WebDriver driver) {
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
    } catch (Exception e) {
      log.debug("Error clicking checkboxes: {}", e.getMessage());
    }
  }

  /**
   * Fill ALL listboxes/comboboxes in the current section
   */
  private void fillAllListboxes(WebDriver driver) {
    try {
      List<WebElement> listboxes = driver.findElements(By.cssSelector("[role='listbox']"));
      int filledListboxes = 0;

      for (WebElement lb : listboxes) {
        if (lb.isDisplayed()) {
          try {
            lb.click();
            try {
              Thread.sleep(200);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }

            // Try to find and click the first option
            List<WebElement> options = driver.findElements(By.cssSelector("[role='option']"));
            boolean optionClicked = false;

            for (WebElement option : options) {
              if (option.isDisplayed()) {
                try {
                  option.click();
                  optionClicked = true;
                  filledListboxes++;
                  log.debug("Selected first option in listbox");
                  break;
                } catch (Exception e) {
                  log.debug("Failed to click listbox option: {}", e.getMessage());
                }
              }
            }

            // If no option was clicked, try Enter key
            if (!optionClicked) {
              lb.sendKeys(Keys.ENTER);
              filledListboxes++;
              log.debug("Used Enter key for listbox");
            }
          } catch (Exception e) {
            log.debug("Failed to interact with listbox: {}", e.getMessage());
          }
        }
      }

      if (filledListboxes > 0) {
        log.info("Filled {} listboxes", filledListboxes);
      }
    } catch (Exception e) {
      log.debug("Error filling listboxes: {}", e.getMessage());
    }
  }

  private void tryClickFirstInEachRow(WebDriver driver, By optionLocator) {
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
    } catch (Exception e) {
      log.debug("Error clicking grid options: {}", e.getMessage());
    }
  }
}


