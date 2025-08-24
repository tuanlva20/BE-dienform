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
 * Dedicated handler for combobox/dropdown question types
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
    return findDropdownTriggerEnhanced(questionElement, questionTitle);
  }

  /**
   * Enhanced dropdown trigger detection for headless mode
   */
  private WebElement findDropdownTriggerEnhanced(WebElement questionElement, String questionTitle) {
    try {
      // Strategy 1: Standard role='listbox' selector
      try {
        WebElement dropdownTrigger =
            questionElement.findElement(By.cssSelector("[role='listbox']"));
        if (dropdownTrigger != null && dropdownTrigger.isDisplayed()) {
          return dropdownTrigger;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 2: Look for elements with aria-expanded attribute
      try {
        List<WebElement> candidates =
            questionElement.findElements(By.cssSelector("[aria-expanded]"));
        for (WebElement candidate : candidates) {
          if (candidate.isDisplayed() && candidate.isEnabled()) {
            return candidate;
          }
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 3: Look for clickable elements within question
      try {
        List<WebElement> candidates = questionElement
            .findElements(By.cssSelector("[tabindex], [role='button'], [role='listbox']"));
        for (WebElement candidate : candidates) {
          if (candidate.isDisplayed() && candidate.isEnabled()) {
            return candidate;
          }
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 4: Look for any interactive element
      try {
        List<WebElement> candidates =
            questionElement.findElements(By.cssSelector("[onclick], [jsaction], [tabindex]"));
        for (WebElement candidate : candidates) {
          if (candidate.isDisplayed() && candidate.isEnabled()) {
            return candidate;
          }
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      log.error("No dropdown trigger found within question element for: {}", questionTitle);
      return null;
    } catch (Exception e) {
      log.error("Enhanced dropdown trigger detection failed for {}: {}", questionTitle,
          e.getMessage());
      return null;
    }
  }

  /**
   * Expand the dropdown by clicking on the trigger
   */
  private boolean expandDropdown(WebDriver driver, WebElement dropdownTrigger, String questionTitle,
      WebDriverWait wait) {
    return expandDropdownEnhanced(driver, dropdownTrigger, questionTitle, wait);
  }

  /**
   * Enhanced dropdown expansion for headless mode
   */
  private boolean expandDropdownEnhanced(WebDriver driver, WebElement dropdownTrigger,
      String questionTitle, WebDriverWait wait) {
    try {
      // Check if already expanded
      String initialExpanded = dropdownTrigger.getAttribute("aria-expanded");
      if ("true".equals(initialExpanded)) {
        return true;
      }

      // Enhanced scrolling for headless mode
      scrollToElementEnhanced(driver, dropdownTrigger);

      // Multiple click strategies with verification
      boolean expanded = false;

      // Strategy 1: JavaScript click with verification
      try {
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", dropdownTrigger);
        Thread.sleep(500);
        expanded = verifyDropdownExpanded(dropdownTrigger);
        if (expanded) {
          return true;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 2: Focus + Enter key
      try {
        dropdownTrigger.sendKeys(Keys.TAB);
        Thread.sleep(200);
        dropdownTrigger.sendKeys(Keys.ENTER);
        Thread.sleep(500);
        expanded = verifyDropdownExpanded(dropdownTrigger);
        if (expanded) {
          return true;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 3: Space key
      try {
        dropdownTrigger.sendKeys(Keys.SPACE);
        Thread.sleep(500);
        expanded = verifyDropdownExpanded(dropdownTrigger);
        if (expanded) {
          return true;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 4: Regular click as last resort
      try {
        dropdownTrigger.click();
        Thread.sleep(500);
        expanded = verifyDropdownExpanded(dropdownTrigger);
        if (expanded) {
          return true;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 5: Additional JavaScript click with longer wait
      try {
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", dropdownTrigger);
        Thread.sleep(800);
        expanded = verifyDropdownExpanded(dropdownTrigger);
        if (expanded) {
          return true;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      log.warn("All click strategies failed for: {}", questionTitle);
      return false;

    } catch (Exception e) {
      log.error("Enhanced dropdown expansion failed for {}: {}", questionTitle, e.getMessage());
      return false;
    }
  }

  /**
   * Enhanced scrolling for headless mode
   */
  private void scrollToElementEnhanced(WebDriver driver, WebElement element) {
    try {
      // Multiple scrolling strategies
      ((JavascriptExecutor) driver).executeScript(
          "arguments[0].scrollIntoView({block: 'center', inline: 'center'});", element);
      Thread.sleep(300);

      ((JavascriptExecutor) driver).executeScript(
          "window.scrollTo(0, arguments[0].offsetTop - window.innerHeight/2);", element);
      Thread.sleep(200);

      // Force element to be visible
      ((JavascriptExecutor) driver).executeScript("arguments[0].style.visibility = 'visible';"
          + "arguments[0].style.display = 'block';" + "arguments[0].style.opacity = '1';", element);
      Thread.sleep(200);
    } catch (Exception e) {
      // Ignore scrolling errors
    }
  }

  /**
   * Verify dropdown is expanded
   */
  private boolean verifyDropdownExpanded(WebElement dropdownTrigger) {
    try {
      String ariaExpanded = dropdownTrigger.getAttribute("aria-expanded");
      return "true".equals(ariaExpanded);
    } catch (Exception e) {
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

      // Strategy 1: Look for popup container with role="option" and data-value (most stable)
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and .//div[@role='option' and @data-value] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          return closestPopup;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 2: Look for popup container with role="option" and jsslot span
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and .//div[@role='option'] and .//span[@jsslot] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          return closestPopup;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 3: Look for popup container with aria-selected attribute
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and .//div[@aria-selected] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          return closestPopup;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 4: Look for popup container with tabindex attribute
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and .//div[@tabindex] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          return closestPopup;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 5: Look for any visible popup container with role="option"
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and .//div[@role='option'] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          return closestPopup;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 6: Fallback - look for any visible popup container
      try {
        List<WebElement> popupContainers = driver.findElements(By.xpath(
            "//div[@role='presentation' and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

        if (!popupContainers.isEmpty()) {
          WebElement closestPopup = findClosestPopup(popupContainers, triggerLocation);
          return closestPopup;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      // Strategy 7: As a final fallback, some forms render options inside the listbox itself.
      // If the dropdown is expanded and contains role='option' children, use the trigger as
      // container.
      try {
        String expanded = dropdownTrigger.getAttribute("aria-expanded");
        List<WebElement> inlineOptions =
            dropdownTrigger.findElements(By.cssSelector("[role='option']"));
        if ("true".equals(expanded) && !inlineOptions.isEmpty()) {
          return dropdownTrigger;
        }
      } catch (Exception e) {
        // Continue to next strategy
      }

      return null;
    } catch (Exception e) {
      // Continue to next strategy
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

      } catch (Exception e) {
        // Continue to next popup
      }
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
        return false;
      }

      // Try to wait for at least one option to be clickable
      try {
        wait.until(ExpectedConditions.elementToBeClickable(options.get(0)));
        return true;
      } catch (Exception e) {
        return false;
      }
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Find option element using stable attributes (no dynamic classes or jsname) Prioritizes options
   * with tabindex="0" when available
   */
  private WebElement findOptionByStableAttributes(WebElement popupContainer, String optionText) {
    try {
      String normalizedOptionText = normalize(optionText);
      List<WebElement> options = popupContainer.findElements(By.cssSelector("[role='option']"));

      // First pass: Look for exact match with tabindex="0" priority
      WebElement preferredMatch = null;
      WebElement exactMatch = null;
      WebElement partialMatch = null;

      for (int i = 0; i < options.size(); i++) {
        WebElement option = options.get(i);
        try {
          if (!option.isDisplayed()) {
            continue;
          }

          String tabindex = option.getAttribute("tabindex");
          boolean isPreferred = "0".equals(tabindex);

          // Strategy 1: Check data-value attribute (most stable - from HTML example)
          String dataValue = normalize(option.getAttribute("data-value"));
          if (!dataValue.isEmpty()) {
            if (dataValue.equals(normalizedOptionText)) {
              if (isPreferred) {
                return option;
              } else if (exactMatch == null) {
                exactMatch = option;
              }
            } else if (dataValue.contains(normalizedOptionText)) {
              if (isPreferred && preferredMatch == null) {
                preferredMatch = option;
              } else if (partialMatch == null) {
                partialMatch = option;
              }
            }
          }

          // Strategy 2: Check aria-label attribute
          String ariaLabel = normalize(option.getAttribute("aria-label"));
          if (!ariaLabel.isEmpty()) {
            if (ariaLabel.equals(normalizedOptionText)) {
              if (isPreferred) {
                return option;
              } else if (exactMatch == null) {
                exactMatch = option;
              }
            } else if (ariaLabel.contains(normalizedOptionText)) {
              if (isPreferred && preferredMatch == null) {
                preferredMatch = option;
              } else if (partialMatch == null) {
                partialMatch = option;
              }
            }
          }

          // Strategy 3: Check span text with jsslot attribute (stable attribute)
          try {
            WebElement span = option.findElement(By.cssSelector("span[jsslot]"));
            String spanText = normalize(span.getText());
            if (!spanText.isEmpty()) {
              if (spanText.equals(normalizedOptionText)) {
                if (isPreferred) {
                  return option;
                } else if (exactMatch == null) {
                  exactMatch = option;
                }
              } else if (spanText.contains(normalizedOptionText)) {
                if (isPreferred && preferredMatch == null) {
                  preferredMatch = option;
                } else if (partialMatch == null) {
                  partialMatch = option;
                }
              }
            }
          } catch (Exception e) {
            // Span not found, continue
          }

          // Strategy 4: Check any text content in the option
          String optionTextContent = normalize(option.getText());
          if (!optionTextContent.isEmpty()) {
            if (optionTextContent.equals(normalizedOptionText)) {
              if (isPreferred) {
                return option;
              } else if (exactMatch == null) {
                exactMatch = option;
              }
            } else if (optionTextContent.contains(normalizedOptionText)) {
              if (isPreferred && preferredMatch == null) {
                preferredMatch = option;
              } else if (partialMatch == null) {
                partialMatch = option;
              }
            }
          }

          // Strategy 5: Check aria-selected attribute for selected options
          String ariaSelected = option.getAttribute("aria-selected");
          if ("true".equals(ariaSelected)) {
            // If this option is selected, check if it matches our target
            String selectedText = normalize(option.getText());
            if (!selectedText.isEmpty()) {
              if (selectedText.equals(normalizedOptionText)) {
                if (isPreferred) {
                  return option;
                } else if (exactMatch == null) {
                  exactMatch = option;
                }
              }
            }
          }

        } catch (Exception e) {
          // Continue to next option
        }
      }

      // Return the best match found (preferred > exact > partial)
      if (preferredMatch != null) {
        return preferredMatch;
      } else if (exactMatch != null) {
        return exactMatch;
      } else if (partialMatch != null) {
        return partialMatch;
      }

      return null;
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Select an option from a popup container with enhanced reliability
   */
  private boolean selectOptionFromPopup(WebDriver driver, WebElement popupContainer,
      String optionText, String questionTitle, WebDriverWait wait) {
    try {
      // Wait for popup to be fully loaded and ready
      if (!waitForPopupReady(driver, popupContainer, wait)) {
        return false;
      }

      // Find the target option using stable attributes
      WebElement targetOption = findOptionByStableAttributes(popupContainer, optionText);
      if (targetOption == null) {
        return false;
      }

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
        clickSuccess = true;
      } catch (Exception spanClickException) {
        // Continue to next strategy
      }

      // Strategy 2: Try JavaScript click on the option element
      if (!clickSuccess) {
        try {
          ((JavascriptExecutor) driver).executeScript("arguments[0].click();", targetOption);
          clickSuccess = true;
        } catch (Exception jsClickException) {
          // Continue to next strategy
        }
      }

      // Strategy 3: Try regular click with explicit wait
      if (!clickSuccess) {
        try {
          wait.until(ExpectedConditions.elementToBeClickable(targetOption));
          targetOption.click();
          clickSuccess = true;
        } catch (Exception regularClickException) {
          // Continue to next strategy
        }
      }

      // Strategy 4: Try using Enter key on the option
      if (!clickSuccess) {
        try {
          targetOption.sendKeys(Keys.ENTER);
          clickSuccess = true;
        } catch (Exception enterKeyException) {
          // Continue to next strategy
        }
      }

      // Strategy 5: Try using Space key on the option
      if (!clickSuccess) {
        try {
          targetOption.sendKeys(Keys.SPACE);
          clickSuccess = true;
        } catch (Exception spaceKeyException) {
          // Continue to next strategy
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
              verified = true;
            }
          } catch (Exception ignore) {
            // Stale element or not visible -> assume closed
            verified = true;
          }
        }

        if (verified) {
          return true;
        } else {
          return false;
        }
      } else {
        return false;
      }

    } catch (Exception e) {
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
            return true;
          }

          // Also check aria-label attribute
          String ariaLabel = trigger.getAttribute("aria-label");
          if (ariaLabel != null && normalize(ariaLabel).contains(normalizedOptionText)) {
            return true;
          }
        } catch (Exception e) {
          // Continue to next trigger
        }
      }

      return false;
    } catch (Exception e) {
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


