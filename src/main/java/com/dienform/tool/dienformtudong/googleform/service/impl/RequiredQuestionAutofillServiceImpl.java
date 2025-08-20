package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.time.Duration;
import java.util.List;
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

  @Override
  public boolean satisfyRequiredQuestions(WebDriver driver) {
    try {
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));
      wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));

      // Text inputs
      fillFirstVisible(driver, By.cssSelector("input[type='text']"), "N/A");
      fillFirstVisible(driver, By.cssSelector("input[type='email']"), "test@example.com");
      fillFirstVisible(driver, By.tagName("textarea"), "N/A");

      // Radio: pick first
      clickFirstVisible(driver, By.cssSelector("[role='radio']"));

      // Checkbox: tick first
      clickFirstVisible(driver, By.cssSelector("[role='checkbox']"));

      // Combobox/Listbox: open then select first option
      List<WebElement> listboxes = driver.findElements(By.cssSelector("[role='listbox']"));
      if (!listboxes.isEmpty()) {
        WebElement lb = listboxes.get(0);
        if (lb.isDisplayed()) {
          lb.click();
          try {
            Thread.sleep(200);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          List<WebElement> options = driver.findElements(By.cssSelector("[role='option']"));
          if (!options.isEmpty() && options.get(0).isDisplayed()) {
            options.get(0).click();
          } else {
            // try confirm with Enter if options rendered virtually
            lb.sendKeys(Keys.ENTER);
          }
        }
      }

      // Multiple choice grid: for each row pick first radio
      tryClickFirstInEachRow(driver, By.cssSelector("[role='radio']"));

      // Checkbox grid: pick at least one checkbox
      clickFirstVisible(driver, By.cssSelector("[role='checkbox']"));

      return true;
    } catch (Exception e) {
      log.debug("satisfyRequiredQuestions failed: {}", e.getMessage());
      return false;
    }
  }

  private void fillFirstVisible(WebDriver driver, By locator, String value) {
    try {
      List<WebElement> elements = driver.findElements(locator);
      for (WebElement el : elements) {
        if (el.isDisplayed() && el.isEnabled()) {
          el.clear();
          el.sendKeys(value);
          break;
        }
      }
    } catch (Exception ignored) {
    }
  }

  private void clickFirstVisible(WebDriver driver, By locator) {
    try {
      List<WebElement> elements = driver.findElements(locator);
      for (WebElement el : elements) {
        if (el.isDisplayed() && el.isEnabled()) {
          el.click();
          break;
        }
      }
    } catch (Exception ignored) {
    }
  }

  private void tryClickFirstInEachRow(WebDriver driver, By optionLocator) {
    try {
      // Heuristic: find groups of radios in grid rows; here we simply try first 3 radios
      List<WebElement> radios = driver.findElements(optionLocator);
      int clicked = 0;
      for (WebElement r : radios) {
        if (clicked >= 3)
          break;
        if (r.isDisplayed() && r.isEnabled()) {
          try {
            r.click();
            clicked++;
          } catch (Exception ignored) {
          }
        }
      }
    } catch (Exception ignored) {
    }
  }
}


