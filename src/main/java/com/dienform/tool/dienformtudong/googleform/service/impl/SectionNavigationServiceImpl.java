package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.googleform.service.RequiredQuestionAutofillService;
import com.dienform.tool.dienformtudong.googleform.service.SectionNavigationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SectionNavigationServiceImpl implements SectionNavigationService {

  private static final By NEXT_BUTTON = By.xpath(
      "//div[@role='button' and (.//span[normalize-space()='Tiếp'] or .//span[normalize-space()='Next'])]");
  private static final By SUBMIT_BUTTON = By.xpath(
      "//div[@role='button' and (.//span[normalize-space()='Gửi'] or .//span[normalize-space()='Submit'])]");

  private final RequiredQuestionAutofillService requiredQuestionAutofillService;

  @Value("${google.form.timeout-seconds:30}")
  private int timeoutSeconds;

  @Value("${google.form.headless:true}")
  private boolean headless;

  @Override
  public List<String> captureSectionHtmls(String formUrl) {
    WebDriver driver = null;
    try {
      ChromeOptions options = new ChromeOptions();
      if (headless) {
        options.addArguments("--headless=new");
      }
      options.addArguments("--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage");
      driver = new ChromeDriver(options);

      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));
      driver.get(formUrl);

      // First page HTML
      wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));

      boolean hasNextOnFirstPage = isVisible(driver, NEXT_BUTTON);
      boolean hasSubmitOnFirstPage = isVisible(driver, SUBMIT_BUTTON);
      if (!hasNextOnFirstPage && !hasSubmitOnFirstPage) {
        // Probably a single-section form with no Next; let caller fallback to HTTP parser
        return null;
      }

      List<String> htmls = new ArrayList<>();
      int maxSections = 50;
      for (int i = 0; i < maxSections; i++) {
        // Capture current section HTML
        htmls.add(driver.getPageSource());

        // If submit button visible, stop here (do not click Submit)
        if (isVisible(driver, SUBMIT_BUTTON)) {
          log.debug("Submit button visible at section {} - stop navigation", i);
          break;
        }

        // If Next not visible, stop
        if (!isVisible(driver, NEXT_BUTTON)) {
          log.debug("No Next button at section {} - stop navigation", i);
          break;
        }

        // Satisfy required questions minimally before clicking Next
        try {
          requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
        } catch (Exception e) {
          log.debug("Autofill required questions failed (section {}): {}", i, e.getMessage());
        }

        // Click Next
        WebElement next = driver.findElement(NEXT_BUTTON);
        next.click();

        // Wait for section change: either a new heading or presence of form root again
        try {
          Thread.sleep(300); // brief pause to allow DOM update
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
      }

      return htmls;
    } catch (Exception e) {
      log.warn("Section navigation failed: {}", e.getMessage());
      return null;
    } finally {
      if (driver != null) {
        try {
          driver.quit();
        } catch (Exception ignore) {
        }
      }
    }
  }

  @Override
  public List<SectionMetadata> captureSectionMetadata(String formUrl) {
    WebDriver driver = null;
    try {
      ChromeOptions options = new ChromeOptions();
      if (headless) {
        options.addArguments("--headless=new");
      }
      options.addArguments("--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage");
      driver = new ChromeDriver(options);

      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));
      driver.get(formUrl);

      // Wait for form to load
      wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));

      boolean hasNextOnFirstPage = isVisible(driver, NEXT_BUTTON);
      boolean hasSubmitOnFirstPage = isVisible(driver, SUBMIT_BUTTON);
      if (!hasNextOnFirstPage && !hasSubmitOnFirstPage) {
        return null;
      }

      // Skip the first section by clicking Next once
      if (hasNextOnFirstPage) {
        try {
          requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
        } catch (Exception ignore) {
        }
        try {
          WebElement next = driver.findElement(NEXT_BUTTON);
          next.click();
          try {
            Thread.sleep(300);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
          wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
        } catch (Exception e) {
          log.debug("Failed to move past first section: {}", e.getMessage());
          return null;
        }
      }

      List<SectionMetadata> metadataList = new ArrayList<>();
      int maxSections = 50;
      int sectionIndex = 1; // starting from second section as 1
      for (int i = 0; i < maxSections; i++) {
        String title = extractSectionTitleFromFirstListItem(driver, sectionIndex);
        String description = extractSectionDescriptionFromFirstListItem(driver);
        metadataList.add(new SectionMetadata(sectionIndex, title, description));

        if (isVisible(driver, SUBMIT_BUTTON)) {
          break;
        }
        if (!isVisible(driver, NEXT_BUTTON)) {
          break;
        }

        try {
          requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
        } catch (Exception ignore) {
        }
        WebElement next = driver.findElement(NEXT_BUTTON);
        next.click();
        try {
          Thread.sleep(300);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
        sectionIndex++;
      }

      return metadataList;
    } catch (Exception e) {
      log.warn("Section metadata extraction failed: {}", e.getMessage());
      return null;
    } finally {
      if (driver != null) {
        try {
          driver.quit();
        } catch (Exception ignore) {
        }
      }
    }
  }

  private String extractSectionTitleFromFirstListItem(WebDriver driver, int sectionIndex) {
    try {
      List<WebElement> listItems = driver.findElements(By.cssSelector("div[role='listitem']"));
      if (listItems.isEmpty()) {
        return "Section " + sectionIndex;
      }
      WebElement first = listItems.get(0);
      // Prefer stable role-based heading
      List<WebElement> headings =
          first.findElements(By.cssSelector("[role='heading'] .aG9Vid.vVO4xd.M7eMe"));
      for (WebElement el : headings) {
        String text = el.getText().trim();
        if (!text.isEmpty())
          return text;
      }
      // Fallback: any heading text
      List<WebElement> anyHeadings = first.findElements(By.cssSelector("[role='heading']"));
      for (WebElement el : anyHeadings) {
        String text = el.getText().trim();
        if (!text.isEmpty())
          return text;
      }
      return "Section " + sectionIndex;
    } catch (Exception e) {
      return "Section " + sectionIndex;
    }
  }

  private String extractSectionDescriptionFromFirstListItem(WebDriver driver) {
    try {
      List<WebElement> listItems = driver.findElements(By.cssSelector("div[role='listitem']"));
      if (listItems.isEmpty()) {
        return null;
      }
      WebElement first = listItems.get(0);
      List<WebElement> descs = first.findElements(By.cssSelector(".vfQisd.Q8wTDd.OIC90c"));
      for (WebElement el : descs) {
        String text = el.getText().trim();
        if (!text.isEmpty())
          return text;
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  private boolean isVisible(WebDriver driver, By locator) {
    try {
      List<WebElement> elements = driver.findElements(locator);
      for (WebElement el : elements) {
        if (el.isDisplayed())
          return true;
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }
}


