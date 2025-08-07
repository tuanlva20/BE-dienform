package com.dienform.tool.dienformtudong.googleform.handler;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import com.dienform.tool.dienformtudong.googleform.dto.GridQuestionAnswer;
import com.dienform.tool.dienformtudong.googleform.service.GridQuestionService;
import com.dienform.tool.dienformtudong.googleform.service.impl.GridQuestionServiceImpl;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import lombok.extern.slf4j.Slf4j;

/**
 * Handler for filling grid questions in Google Forms Supports both multiple choice grid and
 * checkbox grid questions
 */
@Slf4j
public class GridQuestionHandler {

  private final GridQuestionService gridQuestionService;
  private final Random random = new Random();

  public GridQuestionHandler() {
    this.gridQuestionService = new GridQuestionServiceImpl();
  }

  public GridQuestionHandler(GridQuestionService gridQuestionService) {
    this.gridQuestionService = gridQuestionService;
  }

  /**
   * Fill a multiple choice grid question
   */
  public void fillMultipleChoiceGridQuestion(WebDriver driver, WebElement questionElement,
      Question question, QuestionOption option, boolean humanLike) {
    try {
      log.info("Filling multiple choice grid question: {}", question.getTitle());

      GridQuestionAnswer gridAnswer =
          gridQuestionService.parseGridAnswer(question, option.getText());

      if (gridAnswer.getRowAnswers() == null || gridAnswer.getRowAnswers().isEmpty()) {
        log.warn("No valid row answers found for question: {}", question.getTitle());
        return;
      }

      // Find all row groups in the grid
      List<WebElement> rowGroups =
          questionElement.findElements(By.cssSelector("[role='radiogroup']"));
      log.debug("Found {} row groups for question: {}", rowGroups.size(), question.getTitle());

      for (Map.Entry<String, Object> entry : gridAnswer.getRowAnswers().entrySet()) {
        String rowLabel = entry.getKey();
        Object answerValue = entry.getValue();

        if ("all".equals(rowLabel)) {
          // Fill all rows with the same option
          fillAllRowsWithOption(driver, rowGroups, answerValue, humanLike);
        } else {
          // Fill specific row
          fillSpecificRowWithOption(driver, rowGroups, rowLabel, answerValue, humanLike);
        }
      }

      log.info("Successfully filled multiple choice grid question: {}", question.getTitle());
    } catch (Exception e) {
      log.error("Error filling multiple choice grid question: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to fill multiple choice grid question", e);
    }
  }

  /**
   * Fill a checkbox grid question
   */
  public void fillCheckboxGridQuestion(WebDriver driver, WebElement questionElement,
      Question question, QuestionOption option, boolean humanLike) {
    try {
      log.info("Filling checkbox grid question: {}", question.getTitle());

      GridQuestionAnswer gridAnswer =
          gridQuestionService.parseGridAnswer(question, option.getText());

      if (gridAnswer.getRowAnswers() == null || gridAnswer.getRowAnswers().isEmpty()) {
        log.warn("No valid row answers found for question: {}", question.getTitle());
        return;
      }

      // Find all row groups in the grid - use more specific selector for checkbox grids
      List<WebElement> rowGroups =
          questionElement.findElements(By.cssSelector("[role='group']:not([aria-hidden='true'])"));

      // Filter to only include groups that contain checkboxes
      rowGroups = rowGroups.stream()
          .filter(group -> !group.findElements(By.cssSelector("[role='checkbox']")).isEmpty())
          .collect(java.util.stream.Collectors.toList());

      log.debug("Found {} checkbox row groups for question: {}", rowGroups.size(),
          question.getTitle());

      for (Map.Entry<String, Object> entry : gridAnswer.getRowAnswers().entrySet()) {
        String rowLabel = entry.getKey();
        Object answerValue = entry.getValue();

        if ("all".equals(rowLabel)) {
          // Fill all rows with the same options
          fillAllRowsWithCheckboxOptions(driver, rowGroups, answerValue, humanLike);
        } else {
          // Fill specific row
          fillSpecificRowWithCheckboxOptions(driver, rowGroups, rowLabel, answerValue, humanLike);
        }
      }

      log.info("Successfully filled checkbox grid question: {}", question.getTitle());
    } catch (Exception e) {
      log.error("Error filling checkbox grid question: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to fill checkbox grid question", e);
    }
  }

  /**
   * Fill all rows with the same option (for multiple choice grid)
   */
  private void fillAllRowsWithOption(WebDriver driver, List<WebElement> rowGroups,
      Object answerValue, boolean humanLike) {
    String optionText = answerValue.toString();

    for (WebElement rowGroup : rowGroups) {
      try {
        fillRowWithRadioOption(driver, rowGroup, optionText, humanLike);

        if (humanLike) {
          Thread.sleep(50 + random.nextInt(100));
        }
      } catch (Exception e) {
        log.warn("Failed to fill row with option '{}': {}", optionText, e.getMessage());
      }
    }
  }

  /**
   * Fill specific row with option (for multiple choice grid)
   */
  private void fillSpecificRowWithOption(WebDriver driver, List<WebElement> rowGroups,
      String rowLabel, Object answerValue, boolean humanLike) {
    String optionText = answerValue.toString();

    WebElement targetRow = findRowByLabel(rowGroups, rowLabel);
    if (targetRow != null) {
      fillRowWithRadioOption(driver, targetRow, optionText, humanLike);
    } else {
      log.warn("Row with label '{}' not found", rowLabel);
    }
  }

  /**
   * Fill all rows with the same options (for checkbox grid)
   */
  private void fillAllRowsWithCheckboxOptions(WebDriver driver, List<WebElement> rowGroups,
      Object answerValue, boolean humanLike) {
    List<String> options = parseOptions(answerValue);

    for (WebElement rowGroup : rowGroups) {
      try {
        fillRowWithCheckboxOptions(driver, rowGroup, options, humanLike);

        if (humanLike) {
          Thread.sleep(50 + random.nextInt(100));
        }
      } catch (Exception e) {
        log.warn("Failed to fill row with options '{}': {}", options, e.getMessage());
      }
    }
  }

  /**
   * Fill specific row with options (for checkbox grid)
   */
  private void fillSpecificRowWithCheckboxOptions(WebDriver driver, List<WebElement> rowGroups,
      String rowLabel, Object answerValue, boolean humanLike) {
    List<String> options = parseOptions(answerValue);

    WebElement targetRow = findRowByLabel(rowGroups, rowLabel);
    if (targetRow != null) {
      fillRowWithCheckboxOptions(driver, targetRow, options, humanLike);
    } else {
      log.warn("Row with label '{}' not found", rowLabel);
    }
  }

  /**
   * Fill a row with radio option (multiple choice grid)
   */
  private void fillRowWithRadioOption(WebDriver driver, WebElement rowGroup, String optionText,
      boolean humanLike) {
    try {
      // Find radio buttons in the row group
      List<WebElement> radioButtons = rowGroup.findElements(By.cssSelector("[role='radio']"));

      for (WebElement radio : radioButtons) {
        String dataValue = radio.getAttribute("data-value");
        if (dataValue != null && dataValue.trim().equals(optionText.trim())) {
          // Scroll to element if needed
          ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", radio);

          if (humanLike) {
            Thread.sleep(100 + random.nextInt(200));
          }

          radio.click();
          log.debug("Clicked radio button for option: {}", optionText);
          return;
        }
      }

      log.warn("Radio button for option '{}' not found in row", optionText);
    } catch (Exception e) {
      log.error("Error filling row with radio option: {}", e.getMessage());
    }
  }

  /**
   * Fill a row with checkbox options (checkbox grid)
   */
  private void fillRowWithCheckboxOptions(WebDriver driver, WebElement rowGroup,
      List<String> options, boolean humanLike) {
    try {
      // Find checkboxes in the row group
      List<WebElement> checkboxes = rowGroup.findElements(By.cssSelector("[role='checkbox']"));
      log.debug("Found {} checkboxes in row group", checkboxes.size());

      for (String optionText : options) {
        boolean optionFound = false;

        for (WebElement checkbox : checkboxes) {
          try {
            // Try multiple ways to identify the checkbox
            String ariaLabel = checkbox.getAttribute("aria-label");
            String dataAnswerValue = checkbox.getAttribute("data-answer-value");

            if ((ariaLabel != null && ariaLabel.contains(optionText))
                || (dataAnswerValue != null && dataAnswerValue.equals(optionText))) {

              // Check if checkbox is already selected
              String ariaChecked = checkbox.getAttribute("aria-checked");
              if ("true".equals(ariaChecked)) {
                log.debug("Checkbox for option '{}' is already selected", optionText);
                optionFound = true;
                break;
              }

              // Scroll to element if needed
              ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);",
                  checkbox);

              if (humanLike) {
                Thread.sleep(100 + random.nextInt(200));
              }

              // Try JavaScript click first for better reliability
              try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", checkbox);
                log.debug("Clicked checkbox for option '{}' using JavaScript", optionText);
              } catch (Exception jsError) {
                log.debug("JavaScript click failed, trying regular click: {}",
                    jsError.getMessage());
                checkbox.click();
                log.debug("Clicked checkbox for option '{}' using regular click", optionText);
              }

              optionFound = true;
              break;
            }
          } catch (Exception e) {
            log.debug("Error processing checkbox: {}", e.getMessage());
            continue;
          }
        }

        if (!optionFound) {
          log.warn("Checkbox for option '{}' not found in row", optionText);
        }
      }
    } catch (Exception e) {
      log.error("Error filling row with checkbox options: {}", e.getMessage());
    }
  }

  /**
   * Find row by label - improved to handle the specific HTML structure
   */
  private WebElement findRowByLabel(List<WebElement> rowGroups, String targetLabel) {
    for (WebElement rowGroup : rowGroups) {
      String ariaLabel = rowGroup.getAttribute("aria-label");
      if (ariaLabel != null && ariaLabel.trim().equalsIgnoreCase(targetLabel.trim())) {
        log.debug("Found row with label: {}", targetLabel);
        return rowGroup;
      }
    }
    log.warn("Row with label '{}' not found", targetLabel);
    return null;
  }

  /**
   * Parse options from answer value
   */
  private List<String> parseOptions(Object answerValue) {
    if (answerValue instanceof List) {
      return (List<String>) answerValue;
    } else {
      String text = answerValue.toString();
      if (text.contains(",")) {
        return List.of(text.split(","));
      } else {
        return List.of(text);
      }
    }
  }
}
