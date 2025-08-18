package com.dienform.tool.dienformtudong.googleform.handler;

import java.util.List;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/**
 * Dedicated handler for combobox/dropdown question types This class is separated from
 * GoogleFormServiceImpl to avoid affecting other question types
 */
@Component
@Slf4j
public class ComboboxHandler {

  /**
   * Fill a combobox question with the specified option
   */
  public boolean fillComboboxQuestion(WebDriver driver, WebElement questionElement,
      String questionTitle, String optionText, boolean humanLike) {
    try {
      log.info("Filling combobox question: '{}' with option: '{}'", questionTitle, optionText);

      WebDriverWait wait = new WebDriverWait(driver, java.time.Duration.ofSeconds(10));

      // Find dropdown trigger
      WebElement dropdownTrigger = findDropdownTrigger(questionElement, questionTitle);
      if (dropdownTrigger == null) {
        log.error("Could not find dropdown trigger for question: {}", questionTitle);
        return false;
      }

      // Click to expand dropdown
      if (!expandDropdown(driver, dropdownTrigger, questionTitle, wait)) {
        log.error("Could not expand dropdown for question: {}", questionTitle);
        return false;
      }

      // Find popup container
      WebElement popupContainer = findPopupContainer(driver, dropdownTrigger, wait);
      if (popupContainer == null) {
        log.error("Could not find popup container for question: {}", questionTitle);
        return false;
      }

      // Select option from popup
      boolean success =
          selectOptionFromPopup(driver, popupContainer, optionText, questionTitle, wait);

      if (success) {
        log.info("Successfully filled combobox question: '{}'", questionTitle);
      } else {
        log.error("Failed to fill combobox question: '{}'", questionTitle);
      }

      return success;

    } catch (Exception e) {
      log.error("Error filling combobox question '{}': {}", questionTitle, e.getMessage());
      return false;
    }
  }

  /**
   * Find dropdown trigger element within the question
   */
  private WebElement findDropdownTrigger(WebElement questionElement, String questionTitle) {
    try {
      // Look for dropdown trigger specifically within this question element
      WebElement dropdownTrigger = questionElement.findElement(By.cssSelector("[role='listbox']"));

      if (dropdownTrigger == null) {
        log.error("No dropdown trigger found within question element for: {}", questionTitle);
        return null;
      }

      log.debug("Found dropdown trigger for question: {}", questionTitle);
      return dropdownTrigger;

    } catch (Exception e) {
      log.error("No dropdown trigger found for question: {}", questionTitle);
      return null;
    }
  }

  /**
   * Expand the dropdown by clicking on the trigger
   */
  private boolean expandDropdown(WebDriver driver, WebElement dropdownTrigger, String questionTitle,
      WebDriverWait wait) {
    try {
      // If it's already expanded, don't toggle it closed
      try {
        String initialExpanded = dropdownTrigger.getAttribute("aria-expanded");
        if ("true".equals(initialExpanded)) {
          log.debug("Dropdown already expanded for question: {}", questionTitle);
          return true;
        }
      } catch (Exception ignore) {
      }

      // Scroll to the dropdown trigger to ensure it's visible and clickable
      try {
        ((JavascriptExecutor) driver)
            .executeScript("arguments[0].scrollIntoView({block: 'center'});", dropdownTrigger);
        Thread.sleep(200);
      } catch (Exception e) {
        log.debug("Could not scroll to dropdown trigger: {}", e.getMessage());
      }

      // Click to expand the dropdown with multiple strategies
      wait.until(ExpectedConditions.elementToBeClickable(dropdownTrigger));

      // Strategy 1: Regular click
      dropdownTrigger.click();
      log.info("Clicked dropdown trigger for question: {}", questionTitle);

      // Verify dropdown expanded
      Thread.sleep(500);
      String ariaExpanded = dropdownTrigger.getAttribute("aria-expanded");
      if (!"true".equals(ariaExpanded)) {
        log.warn("Dropdown not expanded after regular click, trying JavaScript click");
        try {
          // Strategy 2: JavaScript click
          ((JavascriptExecutor) driver).executeScript("arguments[0].click();", dropdownTrigger);
          Thread.sleep(500);
          ariaExpanded = dropdownTrigger.getAttribute("aria-expanded");
        } catch (Exception e) {
          log.debug("JavaScript click failed: {}", e.getMessage());
        }

        if (!"true".equals(ariaExpanded)) {
          // Strategy 3: Keyboard toggle as fallback
          try {
            dropdownTrigger.sendKeys(Keys.SPACE);
            Thread.sleep(400);
            ariaExpanded = dropdownTrigger.getAttribute("aria-expanded");
          } catch (Exception e) {
            log.debug("Space key failed: {}", e.getMessage());
          }
        }

        if (!"true".equals(ariaExpanded)) {
          try {
            dropdownTrigger.sendKeys(Keys.ENTER);
            Thread.sleep(400);
            ariaExpanded = dropdownTrigger.getAttribute("aria-expanded");
          } catch (Exception e) {
            log.debug("Enter key failed: {}", e.getMessage());
          }
        }

        if (!"true".equals(ariaExpanded)) {
          log.warn("Dropdown still not expanded after all strategies");
          return false;
        }
      }

      // Wait a bit for dropdown to expand
      Thread.sleep(1000);

      log.debug("Dropdown expanded successfully for question: {}", questionTitle);
      return true;

    } catch (Exception e) {
      log.error("Error expanding dropdown for question '{}': {}", questionTitle, e.getMessage());
      return false;
    }
  }

  /**
   * Find popup container using stable selectors (no dynamic jsname or classes) Ensures we find the
   * popup closest to the current dropdown trigger
   */
  private WebElement findPopupContainer(WebDriver driver, WebElement dropdownTrigger,
      WebDriverWait wait) {
    try {
      // Get the location of the dropdown trigger to find the closest popup
      org.openqa.selenium.Point triggerLocation = dropdownTrigger.getLocation();
      log.debug("Dropdown trigger location: x={}, y={}", triggerLocation.getX(),
          triggerLocation.getY());

      // Strategy 1: Look for popup container with role="option" and data-value (most stable)
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and .//div[@role='option' and @data-value] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          log.debug(
              "Found popup container with role='option' and data-value, closest to dropdown trigger");
          return closestPopup;
        }
      } catch (Exception e) {
        log.debug("Could not find popup container with role='option' and data-value: {}",
            e.getMessage());
      }

      // Strategy 2: Look for popup container with role="option" and jsslot span
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and .//div[@role='option'] and .//span[@jsslot] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          log.debug(
              "Found popup container with role='option' and jsslot span, closest to dropdown trigger");
          return closestPopup;
        }
      } catch (Exception e) {
        log.debug("Could not find popup container with role='option' and jsslot span: {}",
            e.getMessage());
      }

      // Strategy 3: Look for popup container with aria-selected attribute
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and .//div[@aria-selected] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          log.debug(
              "Found popup container with aria-selected options, closest to dropdown trigger");
          return closestPopup;
        }
      } catch (Exception e) {
        log.debug("Could not find popup container with aria-selected options: {}", e.getMessage());
      }

      // Strategy 4: Look for popup container with tabindex attribute
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and .//div[@tabindex] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          log.debug("Found popup container with tabindex options, closest to dropdown trigger");
          return closestPopup;
        }
      } catch (Exception e) {
        log.debug("Could not find popup container with tabindex options: {}", e.getMessage());
      }

      // Strategy 5: Look for any visible popup container with role="option"
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and .//div[@role='option'] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          log.debug(
              "Found {} popup containers with role='option', using the closest one to dropdown trigger",
              popupContainers.size());
          return closestPopup;
        }
      } catch (Exception e) {
        log.debug("Could not find any popup containers with role='option': {}", e.getMessage());
      }

      // Strategy 6: Fallback - look for any visible popup container
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          log.debug("Found {} general popup containers, using the closest one to dropdown trigger",
              popupContainers.size());
          return closestPopup;
        }
      } catch (Exception e) {
        log.debug("Could not find any general popup containers: {}", e.getMessage());
      }

      // Strategy 7: As a final fallback, some forms render options inside the listbox itself.
      // If the dropdown is expanded and contains role='option' children, use the trigger as
      // container.
      try {
        String expanded = dropdownTrigger.getAttribute("aria-expanded");
        List<WebElement> inlineOptions =
            dropdownTrigger.findElements(By.cssSelector("[role='option']"));
        if ("true".equals(expanded) && !inlineOptions.isEmpty()) {
          log.debug("Using dropdown trigger as popup container (inline options detected: {})",
              inlineOptions.size());
          return dropdownTrigger;
        }
      } catch (Exception e) {
        log.debug("Inline options fallback check failed: {}", e.getMessage());
      }

      return null;
    } catch (Exception e) {
      log.debug("Error finding popup container: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Find the popup container closest to the dropdown trigger
   */
  private WebElement findClosestPopup(List<WebElement> popupContainers,
      org.openqa.selenium.Point triggerLocation) {
    WebElement closestPopup = null;
    double minDistance = Double.MAX_VALUE;

    for (WebElement popup : popupContainers) {
      try {
        org.openqa.selenium.Point popupLocation = popup.getLocation();
        double distance = calculateDistance(triggerLocation, popupLocation);

        if (distance < minDistance) {
          minDistance = distance;
          closestPopup = popup;
        }

        log.debug("Popup at ({}, {}) - distance: {:.2f}", popupLocation.getX(),
            popupLocation.getY(), distance);
      } catch (Exception e) {
        log.debug("Could not get location for popup: {}", e.getMessage());
      }
    }

    if (closestPopup != null) {
      org.openqa.selenium.Point closestLocation = closestPopup.getLocation();
      log.debug("Selected closest popup at ({}, {}) with distance: {:.2f}", closestLocation.getX(),
          closestLocation.getY(), minDistance);
    }

    return closestPopup;
  }

  /**
   * Calculate Euclidean distance between two points
   */
  private double calculateDistance(org.openqa.selenium.Point p1, org.openqa.selenium.Point p2) {
    int dx = p1.getX() - p2.getX();
    int dy = p1.getY() - p2.getY();
    return Math.sqrt(dx * dx + dy * dy);
  }

  /**
   * Wait for popup container to be fully loaded and ready for interaction
   */
  private boolean waitForPopupReady(WebDriver driver, WebElement popupContainer,
      WebDriverWait wait) {
    try {
      // Wait for popup to be visible
      wait.until(ExpectedConditions.visibilityOf(popupContainer));

      // Wait for options to be loaded
      wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[role='option']")));

      // Wait a bit more for any animations to complete
      Thread.sleep(300);

      // Verify that options are actually clickable
      List<WebElement> options = popupContainer.findElements(By.cssSelector("[role='option']"));
      if (options.isEmpty()) {
        log.warn("No options found in popup container after waiting");
        return false;
      }

      // Try to wait for at least one option to be clickable
      try {
        wait.until(ExpectedConditions.elementToBeClickable(options.get(0)));
        log.debug("Popup container is ready with {} options", options.size());
        return true;
      } catch (Exception e) {
        log.warn("Options not clickable after waiting: {}", e.getMessage());
        return false;
      }
    } catch (Exception e) {
      log.error("Error waiting for popup to be ready: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Find option element using stable attributes (no dynamic classes or jsname)
   */
  private WebElement findOptionByStableAttributes(WebElement popupContainer, String optionText) {
    try {
      String normalizedOptionText = normalize(optionText);
      List<WebElement> options = popupContainer.findElements(By.cssSelector("[role='option']"));

      log.debug("Looking for option: '{}' (normalized: '{}') in {} options", optionText,
          normalizedOptionText, options.size());

      for (int i = 0; i < options.size(); i++) {
        WebElement option = options.get(i);
        try {
          log.debug("Checking option {}: {}", i + 1, option.getText());

          // Strategy 1: Check data-value attribute (most stable - from HTML example)
          String dataValue = normalize(option.getAttribute("data-value"));
          if (!dataValue.isEmpty()) {
            log.debug("Option {} data-value: '{}'", i + 1, dataValue);
            if (dataValue.equals(normalizedOptionText)
                || dataValue.contains(normalizedOptionText)) {
              log.debug("Found option by data-value: '{}'", dataValue);
              return option;
            }
          }

          // Strategy 2: Check aria-label attribute
          String ariaLabel = normalize(option.getAttribute("aria-label"));
          if (!ariaLabel.isEmpty()) {
            log.debug("Option {} aria-label: '{}'", i + 1, ariaLabel);
            if (ariaLabel.equals(normalizedOptionText)
                || ariaLabel.contains(normalizedOptionText)) {
              log.debug("Found option by aria-label: '{}'", ariaLabel);
              return option;
            }
          }

          // Strategy 3: Check span text with jsslot attribute (stable attribute)
          try {
            WebElement span = option.findElement(By.cssSelector("span[jsslot]"));
            String spanText = normalize(span.getText());
            if (!spanText.isEmpty()) {
              log.debug("Option {} span[jsslot] text: '{}'", i + 1, spanText);
              if (spanText.equals(normalizedOptionText)
                  || spanText.contains(normalizedOptionText)) {
                log.debug("Found option by span text: '{}'", spanText);
                return option;
              }
            }
          } catch (Exception e) {
            // Span not found, continue
          }

          // Strategy 4: Check any text content in the option
          String optionTextContent = normalize(option.getText());
          if (!optionTextContent.isEmpty()) {
            log.debug("Option {} text content: '{}'", i + 1, optionTextContent);
            if (optionTextContent.equals(normalizedOptionText)
                || optionTextContent.contains(normalizedOptionText)) {
              log.debug("Found option by text content: '{}'", optionTextContent);
              return option;
            }
          }

          // Strategy 5: Check aria-selected attribute for selected options
          String ariaSelected = option.getAttribute("aria-selected");
          if ("true".equals(ariaSelected)) {
            // If this option is selected, check if it matches our target
            String selectedText = normalize(option.getText());
            if (!selectedText.isEmpty()) {
              log.debug("Option {} is selected with text: '{}'", i + 1, selectedText);
              if (selectedText.equals(normalizedOptionText)) {
                log.debug("Found selected option by text: '{}'", selectedText);
                return option;
              }
            }
          }

        } catch (Exception e) {
          log.debug("Error checking option {}: {}", i + 1, e.getMessage());
          continue;
        }
      }

      log.warn("Could not find option '{}' in any of the {} options", optionText, options.size());
      return null;
    } catch (Exception e) {
      log.error("Error finding option by stable attributes: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Select an option from a popup container with enhanced reliability
   */
  private boolean selectOptionFromPopup(WebDriver driver, WebElement popupContainer,
      String optionText, String questionTitle, WebDriverWait wait) {
    try {
      log.debug("Attempting to select option '{}' from popup for question: {}", optionText,
          questionTitle);

      // Wait for popup to be fully loaded and ready
      if (!waitForPopupReady(driver, popupContainer, wait)) {
        log.warn("Popup container not ready for interaction");
        return false;
      }

      // Find the target option using stable attributes
      WebElement targetOption = findOptionByStableAttributes(popupContainer, optionText);
      if (targetOption == null) {
        log.error("Could not find option '{}' in popup container", optionText);
        return false;
      }

      log.debug("Found target option, attempting to click");

      // Enhanced click strategy with multiple approaches
      boolean clickSuccess = false;

      // Ensure the option is in view
      try {
        ((JavascriptExecutor) driver)
            .executeScript("arguments[0].scrollIntoView({block: 'center'});", targetOption);
      } catch (Exception ignore) {
      }

      // Strategy 1: Try clicking the span element with jsslot attribute (stable)
      try {
        WebElement spanElement = targetOption.findElement(By.cssSelector("span[jsslot]"));
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", spanElement);
        log.info("Selected option '{}' for question: {} using span[jsslot] click", optionText,
            questionTitle);
        clickSuccess = true;
      } catch (Exception spanClickException) {
        log.debug("Span[jsslot] click failed: {}", spanClickException.getMessage());
      }

      // Strategy 2: Try JavaScript click on the option element
      if (!clickSuccess) {
        try {
          ((JavascriptExecutor) driver).executeScript("arguments[0].click();", targetOption);
          log.info("Selected option '{}' for question: {} using JavaScript click", optionText,
              questionTitle);
          clickSuccess = true;
        } catch (Exception jsClickException) {
          log.debug("JavaScript click failed: {}", jsClickException.getMessage());
        }
      }

      // Strategy 3: Try regular click with explicit wait
      if (!clickSuccess) {
        try {
          wait.until(ExpectedConditions.elementToBeClickable(targetOption));
          targetOption.click();
          log.info("Selected option '{}' for question: {} using regular click", optionText,
              questionTitle);
          clickSuccess = true;
        } catch (Exception regularClickException) {
          log.debug("Regular click failed: {}", regularClickException.getMessage());
        }
      }

      // Strategy 4: Try using Enter key on the option
      if (!clickSuccess) {
        try {
          targetOption.sendKeys(Keys.ENTER);
          log.info("Selected option '{}' for question: {} using Enter key", optionText,
              questionTitle);
          clickSuccess = true;
        } catch (Exception enterKeyException) {
          log.debug("Enter key failed: {}", enterKeyException.getMessage());
        }
      }

      // Strategy 5: Try using Space key on the option
      if (!clickSuccess) {
        try {
          targetOption.sendKeys(Keys.SPACE);
          log.info("Selected option '{}' for question: {} using Space key", optionText,
              questionTitle);
          clickSuccess = true;
        } catch (Exception spaceKeyException) {
          log.debug("Space key failed: {}", spaceKeyException.getMessage());
        }
      }

      if (clickSuccess) {
        // Wait a bit for the selection to register
        Thread.sleep(200);

        // Verify the selection was successful; if not, also consider popup closure as success
        boolean verified = verifyOptionSelection(driver, optionText, questionTitle);
        if (!verified) {
          try {
            // If popup disappeared, likely the selection was applied
            boolean popupStillVisible = popupContainer.isDisplayed();
            if (!popupStillVisible) {
              log.debug("Popup closed after click; treating selection as successful for '{}': {}",
                  questionTitle, optionText);
              verified = true;
            }
          } catch (Exception ignore) {
            // Stale element or not visible -> assume closed
            log.debug("Popup container not visible/stale; assuming it closed after selection");
            verified = true;
          }
        }

        if (verified) {
          log.info("Option selection verified successfully for question: {}", questionTitle);
          return true;
        } else {
          log.warn("Option selection verification failed for question: {}", questionTitle);
          return false;
        }
      } else {
        log.warn("All click strategies failed for option '{}'", optionText);
        return false;
      }

    } catch (Exception e) {
      log.error("Error selecting option from popup for question '{}': {}", questionTitle,
          e.getMessage());
      return false;
    }
  }

  /**
   * Verify that the option selection was successful by checking the dropdown trigger
   */
  private boolean verifyOptionSelection(WebDriver driver, String optionText, String questionTitle) {
    try {
      // Wait a bit for the selection to be reflected in the UI
      Thread.sleep(300);

      // Look for the dropdown trigger that should now show the selected option
      List<WebElement> dropdownTriggers = driver.findElements(By.cssSelector("[role='listbox']"));

      for (WebElement trigger : dropdownTriggers) {
        try {
          // Check if the trigger contains the selected option text
          String triggerText = trigger.getText();
          String normalizedTriggerText = normalize(triggerText);
          String normalizedOptionText = normalize(optionText);

          if (normalizedTriggerText.contains(normalizedOptionText)) {
            log.debug("Option selection verified: trigger text '{}' contains selected option '{}'",
                triggerText, optionText);
            return true;
          }

          // Also check aria-label attribute
          String ariaLabel = trigger.getAttribute("aria-label");
          if (ariaLabel != null && normalize(ariaLabel).contains(normalizedOptionText)) {
            log.debug("Option selection verified: aria-label '{}' contains selected option '{}'",
                ariaLabel, optionText);
            return true;
          }
        } catch (Exception e) {
          log.debug("Error checking dropdown trigger: {}", e.getMessage());
        }
      }

      log.warn("Could not verify option selection for question: {}", questionTitle);
      return false;
    } catch (Exception e) {
      log.error("Error verifying option selection: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Normalize string for comparison (unicode normalization, trim, lowercase, HTML entities)
   */
  private String normalize(String s) {
    if (s == null)
      return "";

    String normalized = s.replace('\u00A0', ' ') // non-breaking space
        .replace('\u200B', ' ') // zero-width space
        .replace('\u200C', ' ') // zero-width non-joiner
        .replace('\u200D', ' ') // zero-width joiner
        .replace('\u2060', ' ') // word joiner
        .trim();

    // Decode HTML entities
    normalized = normalized.replace("&amp;", "&");
    normalized = normalized.replace("&lt;", "<");
    normalized = normalized.replace("&gt;", ">");
    normalized = normalized.replace("&quot;", "\"");
    normalized = normalized.replace("&#39;", "'");

    // Unicode normalization and lowercase
    normalized =
        java.text.Normalizer.normalize(normalized, java.text.Normalizer.Form.NFC).toLowerCase();
    // Collapse multiple spaces
    normalized = normalized.replaceAll("\\s+", " ");

    return normalized;
  }
}


