package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.googleform.service.FormFillingHelper;
import com.dienform.tool.dienformtudong.googleform.service.RequiredQuestionAutofillService;
import com.dienform.tool.dienformtudong.googleform.service.SectionNavigationService;
import com.dienform.tool.dienformtudong.googleform.service.ValidationResult;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SectionNavigationServiceImpl implements SectionNavigationService {

  private static final By NEXT_BUTTON = By.xpath(
      "//div[@role='button' and (.//span[normalize-space()='Tiếp'] or .//span[normalize-space()='Next'])]");
  private static final By SUBMIT_BUTTON = By.xpath(
      "//div[@role='button' and (.//span[contains(text(), 'Gửi')] or .//span[contains(text(), 'Submit')] or @aria-label='Submit')]");

  private final RequiredQuestionAutofillService requiredQuestionAutofillService;
  private final FormFillingHelper formFillingHelper;

  @Value("${google.form.timeout-seconds:30}")
  private int timeoutSeconds;

  @Value("${google.form.headless:true}")
  private boolean headless;

  @Value("${google.form.auto-submit:true}")
  private boolean autoSubmitEnabled;

  @Override
  public List<String> captureSectionHtmls(String formUrl) {
    WebDriver driver = null;
    try {
      driver = openBrowser(formUrl, false);
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));

      // Clear autofill tracking for new form
      requiredQuestionAutofillService.clearAutofillTracking();

      // Track processed section indices to prevent duplicates
      Set<Integer> processedSectionIndices = new HashSet<>();
      int currentSectionIndex = 0;

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
        // Check if this section index has already been processed
        if (processedSectionIndices.contains(currentSectionIndex)) {
          log.warn("Section index {} already processed, skipping to prevent duplicate",
              currentSectionIndex);
          break;
        }

        // Mark this section index as processed
        processedSectionIndices.add(currentSectionIndex);
        log.info("Processing section index: {} (iteration: {})", currentSectionIndex, i);

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

        // Check if current section has questions
        boolean hasQuestions = hasQuestionsInCurrentSection(driver);

        if (hasQuestions) {
          // Always attempt to satisfy required questions when there are questions
          // Don't rely solely on Next button status as it might be misleading
          try {
            log.info("Section {} has questions, attempting to satisfy required questions", i);
            boolean autofillSuccess =
                requiredQuestionAutofillService.satisfyRequiredQuestions(driver);

            // Check if Next button is ready after autofill
            boolean nextButtonReady = requiredQuestionAutofillService.isNextButtonReady(driver);

            // Only retry if autofill failed AND Next button is still not ready
            if (!autofillSuccess && !nextButtonReady) {
              log.warn("Autofill failed and Next button not ready for section {}, attempting retry",
                  i);
              // Wait a bit and retry
              try {
                Thread.sleep(1000);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
              }
              autofillSuccess = requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
              nextButtonReady = requiredQuestionAutofillService.isNextButtonReady(driver);
            }

            if (autofillSuccess || nextButtonReady) {
              log.info("Successfully satisfied required questions in section {}", i);
            } else {
              log.warn("Failed to satisfy required questions in section {} after retry", i);
            }
          } catch (Exception e) {
            log.warn("Autofill required questions failed (section {}): {}", i, e.getMessage());
          }

          // Final verification that Next button is ready before clicking
          if (!requiredQuestionAutofillService.isNextButtonReady(driver)) {
            log.warn("Next button not ready after autofill in section {}, stopping navigation", i);
            break;
          }
        } else {
          log.info("Section {} has no questions, proceeding directly to Next button", i);
        }

        // Click Next with enhanced retry logic and section change detection
        boolean sectionChanged =
            clickNextWithSectionChangeDetection(driver, wait, i, currentSectionIndex);

        if (!sectionChanged) {
          log.error(
              "Failed to change section after clicking Next button in section index {}, stopping navigation",
              currentSectionIndex);
          break;
        }

        // Increment section index for next iteration
        currentSectionIndex++;
      }

      return htmls;
    } catch (Exception e) {
      log.warn("Section navigation failed: {}", e.getMessage());
      return null;
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
      driver = openBrowser(formUrl, false);
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));

      // Clear autofill tracking for new form
      requiredQuestionAutofillService.clearAutofillTracking();

      // Wait for form to load
      wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));

      boolean hasNextOnFirstPage = isVisible(driver, NEXT_BUTTON);
      boolean hasSubmitOnFirstPage = isVisible(driver, SUBMIT_BUTTON);
      if (!hasNextOnFirstPage && !hasSubmitOnFirstPage) {
        return null;
      }

      // Skip the first section by clicking Next once
      if (hasNextOnFirstPage) {
        // Check if first section has questions
        boolean firstSectionHasQuestions = hasQuestionsInCurrentSection(driver);

        if (firstSectionHasQuestions) {
          // Always attempt to satisfy required questions when there are questions
          // Don't rely solely on Next button status as it might be misleading
          try {
            log.info(
                "First section has questions, attempting to satisfy required questions for metadata capture");
            boolean autofillSuccess =
                requiredQuestionAutofillService.satisfyRequiredQuestions(driver);

            // Check if Next button is ready after autofill
            boolean nextButtonReady = requiredQuestionAutofillService.isNextButtonReady(driver);

            // Only retry if autofill failed AND Next button is still not ready
            if (!autofillSuccess && !nextButtonReady) {
              log.warn(
                  "Autofill failed and Next button not ready for first section, attempting retry");
              // Wait a bit and retry
              try {
                Thread.sleep(1000);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
              }
              autofillSuccess = requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
              nextButtonReady = requiredQuestionAutofillService.isNextButtonReady(driver);
            }

            if (autofillSuccess || nextButtonReady) {
              log.info("Successfully satisfied required questions in first section");
            } else {
              log.warn("Failed to satisfy required questions in first section after retry");
            }
          } catch (Exception e) {
            log.warn("Autofill required questions failed for first section: {}", e.getMessage());
          }

          try {
            // Verify Next button is still available before clicking
            if (!requiredQuestionAutofillService.isNextButtonReady(driver)) {
              log.warn("Next button not ready after autofill in first section");
              return null;
            }
          } catch (Exception e) {
            log.warn("Error checking Next button readiness: {}", e.getMessage());
            return null;
          }
        } else {
          log.info("First section has no questions, proceeding directly to Next button");
        }

        try {
          WebElement next = driver.findElement(NEXT_BUTTON);
          next.click();
          log.info("Successfully clicked Next button in first section");
          try {
            Thread.sleep(300);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
          wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
        } catch (Exception e) {
          log.warn("Failed to move past first section: {}", e.getMessage());
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
          log.info("Submit button visible at section {}, stopping metadata capture", sectionIndex);
          break;
        }
        if (!isVisible(driver, NEXT_BUTTON)) {
          log.info("No Next button at section {}, stopping metadata capture", sectionIndex);
          break;
        }

        try {
          log.info("Attempting to satisfy required questions in section {} for metadata capture",
              sectionIndex);
          boolean autofillSuccess =
              requiredQuestionAutofillService.satisfyRequiredQuestions(driver);

          // Check if Next button is ready after autofill
          boolean nextButtonReady = requiredQuestionAutofillService.isNextButtonReady(driver);

          // Only retry if autofill failed AND Next button is not ready
          if (!autofillSuccess && !nextButtonReady) {
            log.warn("Autofill failed and Next button not ready for section {}, attempting retry",
                sectionIndex);
            // Wait a bit and retry
            try {
              Thread.sleep(1000);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
            autofillSuccess = requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
            nextButtonReady = requiredQuestionAutofillService.isNextButtonReady(driver);
          } else if (autofillSuccess && nextButtonReady) {
            log.info("Autofill succeeded and Next button is ready for section {}", sectionIndex);
          } else if (autofillSuccess && !nextButtonReady) {
            log.info(
                "Autofill succeeded but Next button not ready for section {} (may need more filling)",
                sectionIndex);
          } else {
            log.info(
                "Autofill failed but Next button is ready for section {} (questions may have been already filled)",
                sectionIndex);
          }

          if (autofillSuccess || nextButtonReady) {
            log.info("Successfully satisfied required questions in section {}", sectionIndex);
          } else {
            log.warn("Failed to satisfy required questions in section {} after retry",
                sectionIndex);
          }
        } catch (Exception e) {
          log.warn("Autofill required questions failed for section {}: {}", sectionIndex,
              e.getMessage());
        }

        // Verify Next button is still available before clicking
        if (!requiredQuestionAutofillService.isNextButtonReady(driver)) {
          log.warn("Next button not ready after autofill in section {}, stopping metadata capture",
              sectionIndex);
          break;
        }

        try {
          WebElement next = driver.findElement(NEXT_BUTTON);
          next.click();
          log.info("Successfully clicked Next button in section {} for metadata capture",
              sectionIndex);
          try {
            Thread.sleep(300);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
          wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
        } catch (Exception e) {
          log.warn("Failed to click Next button in section {}: {}", sectionIndex, e.getMessage());
          // Try one more time after a brief wait
          try {
            Thread.sleep(1000);
            WebElement next = driver.findElement(NEXT_BUTTON);
            next.click();
            log.info("Successfully clicked Next button in section {} (retry)", sectionIndex);
            try {
              Thread.sleep(300);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
          } catch (Exception retryEx) {
            log.error("Failed to click Next button in section {} after retry: {}", sectionIndex,
                retryEx.getMessage());
            break;
          }
        }

        sectionIndex++;
      }

      return metadataList;
    } catch (Exception e) {
      log.warn("Section metadata extraction failed: {}", e.getMessage());
      return null;
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

        try {
          driver.quit();
        } catch (Exception ignore) {
        }
      }
    }
  }

  @Override
  public boolean fillSections(String formUrl, Map<Question, QuestionOption> selections,
      boolean humanLike) {
    WebDriver driver = null;
    try {
      driver = openBrowser(formUrl, humanLike);
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

      // Clear autofill tracking for new form
      requiredQuestionAutofillService.clearAutofillTracking();

      try {
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
      } catch (Exception ignore) {
      }

      int sectionCounter = 0;
      int maxSections = 50; // Prevent infinite loops

      while (sectionCounter < maxSections) {
        log.info("Processing section {} (attempt {}/{})", sectionCounter, sectionCounter + 1,
            maxSections);

        // Fill current section using single-section logic
        int filled = fillCurrentSection(driver, selections, humanLike, sectionCounter);

        // CRITICAL FIX: Check for Submit button first
        if (isVisible(driver, SUBMIT_BUTTON)) {
          log.info("Submit button visible at section {} - submitting form", sectionCounter);
          if (autoSubmitEnabled) {
            clickSubmit(driver, wait, humanLike);
            log.info("Form submitted successfully");
          } else {
            log.info("Submit button visible but auto-submit disabled; not submitting");
          }
          return true;
        }

        // Check if Next button is visible
        if (isVisible(driver, NEXT_BUTTON)) {
          log.info("Next button visible at section {} - proceeding to next section",
              sectionCounter);

          // Check if current section has questions
          boolean hasQuestions = hasQuestionsInCurrentSection(driver);

          // If no questions were filled and section has questions, try to satisfy required
          // questions to unlock Next
          if (filled == 0 && hasQuestions) {
            log.info(
                "No questions filled in section {} (has questions), attempting autofill to unlock Next",
                sectionCounter);
            try {
              boolean autofillSuccess =
                  requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
              if (autofillSuccess) {
                log.info("Autofill successful for section {}", sectionCounter);
              } else {
                log.warn("Autofill failed for section {}", sectionCounter);
              }
            } catch (Exception autofillEx) {
              log.warn("Autofill exception for section {}: {}", sectionCounter,
                  autofillEx.getMessage());
            }
          } else if (filled == 0 && !hasQuestions) {
            log.info(
                "No questions filled in section {} (no questions), proceeding directly to Next",
                sectionCounter);
          }

          // Verify Next button is still ready before clicking
          if (!requiredQuestionAutofillService.isNextButtonReady(driver)) {
            log.warn(
                "Next button not ready after autofill in section {}, attempting additional autofill",
                sectionCounter);
            try {
              Thread.sleep(1000);
              requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
            } catch (Exception ignore) {
            }

            // Final check
            if (!requiredQuestionAutofillService.isNextButtonReady(driver)) {
              log.error("Next button still not ready after additional autofill in section {}",
                  sectionCounter);
              return false;
            }
          }

          // Click Next with retry logic
          boolean nextClicked = false;
          int retryCount = 0;
          int maxRetries = 3;

          while (!nextClicked && retryCount < maxRetries) {
            try {
              clickNext(driver, wait, humanLike);
              nextClicked = true;
              log.info("Successfully clicked Next button in section {}", sectionCounter);
            } catch (Exception e) {
              retryCount++;
              log.warn("Failed to click Next button in section {} (attempt {}/{}): {}",
                  sectionCounter, retryCount, maxRetries, e.getMessage());

              if (retryCount < maxRetries) {
                try {
                  Thread.sleep(1000 * retryCount); // Exponential backoff
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                }
              }
            }
          }

          if (!nextClicked) {
            log.error("Failed to click Next button in section {} after {} attempts", sectionCounter,
                maxRetries);
            return false;
          }

          sectionCounter++;

          // Wait for section change
          try {
            Thread.sleep(800);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }

          try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
            log.info("Form element found after section navigation");
          } catch (Exception ignore) {
            log.warn("Could not detect form element change, continuing anyway");
          }

          continue;
        }

        // Neither Next nor Submit visible - this might be an error state
        log.warn("Neither Next nor Submit button visible at section {}", sectionCounter);

        // Try to find Submit button with different selectors as fallback
        try {
          Thread.sleep(1000);
          if (isVisible(driver, SUBMIT_BUTTON)) {
            log.info("Submit button found after additional wait");
            if (autoSubmitEnabled) {
              clickSubmit(driver, wait, humanLike);
            }
            return true;
          }
        } catch (Exception e) {
          log.warn("Error during additional Submit button check: {}", e.getMessage());
        }

        // If we've filled some questions, consider it a partial success
        if (filled > 0) {
          log.info("Partial success: filled {} questions but no navigation buttons found", filled);
          return true;
        }

        log.error("No navigation buttons found and no questions filled at section {}", sectionCounter);
        return false;
      }

      log.error("Reached maximum section limit ({}) without finding Submit button", maxSections);
      return false;

    } catch (Exception e) {
      log.error("fillSections failed: {}", e.getMessage(), e);
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

        try {
          driver.quit();
        } catch (Exception ignore) {
        }
      }
    }
  }

  // ===== Helpers: mimic GoogleFormServiceImpl single-section filling =====

  @Override
  public SectionNavigationResult captureSectionData(String formUrl) {
    WebDriver driver = null;
    try {
      driver = openBrowser(formUrl, false);
      WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));

      // Clear autofill tracking for new form
      requiredQuestionAutofillService.clearAutofillTracking();

      // Track processed section indices to prevent duplicates
      Set<Integer> processedSectionIndices = new HashSet<>();
      int currentSectionIndex = 0;

      // Wait for form to load
      wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));

      boolean hasNextOnFirstPage = isVisible(driver, NEXT_BUTTON);
      boolean hasSubmitOnFirstPage = isVisible(driver, SUBMIT_BUTTON);
      if (!hasNextOnFirstPage && !hasSubmitOnFirstPage) {
        // Probably a single-section form with no Next; let caller fallback to HTTP parser
        return null;
      }

      List<String> htmls = new ArrayList<>();
      List<SectionMetadata> metadataList = new ArrayList<>();
      int maxSections = 50;

      // Capture first section HTML
      htmls.add(driver.getPageSource());

      // If submit button visible on first page, return early
      if (isVisible(driver, SUBMIT_BUTTON)) {
        log.debug("Submit button visible on first page - single section form");
        return new SectionNavigationResult(htmls, metadataList);
      }

      // Navigate through sections and capture both HTML and metadata
      for (int i = 0; i < maxSections; i++) {
        // Check if this section index has already been processed
        if (processedSectionIndices.contains(currentSectionIndex)) {
          log.warn("Section index {} already processed, skipping to prevent duplicate",
              currentSectionIndex);
          break;
        }

        // Mark this section index as processed
        processedSectionIndices.add(currentSectionIndex);
        log.info("Processing section index: {} (iteration: {})", currentSectionIndex, i);

        // Check if current section has questions
        boolean hasQuestions = hasQuestionsInCurrentSection(driver);

        if (hasQuestions) {
          // Always attempt to satisfy required questions when there are questions
          // Don't rely solely on Next button status as it might be misleading
          try {
            log.info("Section {} has questions, attempting to satisfy required questions", i);
            boolean autofillSuccess =
                requiredQuestionAutofillService.satisfyRequiredQuestions(driver);

            // Check if Next button is ready after autofill
            boolean nextButtonReady = requiredQuestionAutofillService.isNextButtonReady(driver);

            // Only retry if autofill failed AND Next button is still not ready
            if (!autofillSuccess && !nextButtonReady) {
              log.warn("Autofill failed and Next button not ready for section {}, attempting retry",
                  i);
              // Wait a bit and retry
              try {
                Thread.sleep(1000);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
              }
              autofillSuccess = requiredQuestionAutofillService.satisfyRequiredQuestions(driver);
              nextButtonReady = requiredQuestionAutofillService.isNextButtonReady(driver);
            }

            if (autofillSuccess || nextButtonReady) {
              log.info("Successfully satisfied required questions in section {}", i);
            } else {
              log.warn("Failed to satisfy required questions in section {} after retry", i);
            }
          } catch (Exception e) {
            log.warn("Autofill required questions failed (section {}): {}", i, e.getMessage());
          }

          // Final verification that Next button is ready before clicking
          if (!requiredQuestionAutofillService.isNextButtonReady(driver)) {
            log.warn("Next button not ready after autofill in section {}, stopping navigation", i);
            break;
          }
        } else {
          log.info("Section {} has no questions, proceeding directly to Next button", i);
        }

        // Click Next with enhanced retry logic and section change detection
        boolean sectionChanged =
            clickNextWithSectionChangeDetection(driver, wait, i, currentSectionIndex);

        if (!sectionChanged) {
          log.error(
              "Failed to change section after clicking Next button in section index {}, stopping navigation",
              currentSectionIndex);
          break;
        }

        // Increment section index for next iteration
        currentSectionIndex++;

        // Capture current section HTML
        htmls.add(driver.getPageSource());

        // Extract metadata for this section (section index starts from 1 for non-first sections)
        int sectionIndex = i + 1;
        String title = extractSectionTitleFromFirstListItem(driver, sectionIndex);
        String description = extractSectionDescriptionFromFirstListItem(driver);
        metadataList.add(new SectionMetadata(sectionIndex, title, description));

        // If submit button visible, stop here (do not click Submit)
        if (isVisible(driver, SUBMIT_BUTTON)) {
          log.debug("Submit button visible at section {} - stop navigation", i + 1);
          break;
        }

        // If Next not visible, stop
        if (!isVisible(driver, NEXT_BUTTON)) {
          log.debug("No Next button at section {} - stop navigation", i + 1);
          break;
        }
      }

      log.info("Captured {} sections with {} metadata entries", htmls.size(), metadataList.size());
      return new SectionNavigationResult(htmls, metadataList);

    } catch (Exception e) {
      log.warn("Section navigation failed: {}", e.getMessage());
      return null;
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

        try {
          driver.quit();
        } catch (Exception ignore) {
        }
      }
    }
  }

  private WebDriver openBrowser(String formUrl, boolean humanLike) {
    ChromeOptions options = new ChromeOptions();
    options.addArguments("--remote-allow-origins=*");
    options.addArguments("--no-sandbox");
    options.addArguments("--disable-dev-shm-usage");
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
    options.setPageLoadStrategy(org.openqa.selenium.PageLoadStrategy.EAGER);

    java.util.Map<String, Object> prefs = new java.util.HashMap<>();
    prefs.put("profile.managed_default_content_settings.images", 2);
    options.setExperimentalOption("prefs", prefs);

    if (headless) {
      options.addArguments("--headless=new");
    }

    options.setExperimentalOption("excludeSwitches",
        java.util.Collections.singletonList("enable-automation"));
    options.setExperimentalOption("useAutomationExtension", false);

    WebDriver driver = new ChromeDriver(options);

    driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(10));
    driver.manage().timeouts().implicitlyWait(Duration.ZERO);

    WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    driver.get(formUrl);

    try {
      wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@role='listitem']")));
    } catch (Exception e) {
      // proceed
    }
    return driver;
  }

  private int fillCurrentSection(WebDriver driver, Map<Question, QuestionOption> selections,
      boolean humanLike, int sectionIndex) {
    int processed = 0;
    try {
      List<WebElement> questionElements =
          driver.findElements(By.cssSelector("div[role='listitem']"));
      new WebDriverWait(driver, Duration.ofSeconds(10));

      for (Map.Entry<Question, QuestionOption> entry : selections.entrySet()) {
        Question question = entry.getKey();
        QuestionOption option = entry.getValue();
        if (option == null)
          continue;

        // Resolution rules:
        // - sectionIndex == 0 (first section): use position primarily
        // - sectionIndex >= 1: use additionalData.section_index primarily
        WebElement questionElement = null;
        if (sectionIndex == 0) {
          // First section: prefer liIndex (0-based) for questions without section_index
          Map<String, String> add = question.getAdditionalData();
          String secIdxStr = add != null ? add.get("section_index") : null;
          String liIndexStr = add != null ? add.get("liIndex") : null;

          if (secIdxStr == null && liIndexStr != null) {
            try {
              int li = Integer.parseInt(liIndexStr);
              if (questionElements != null && li >= 0 && li < questionElements.size()) {
                questionElement = questionElements.get(li);
              }
            } catch (Exception ignore) {
            }
          }

          // Next: position (0-based)
          if (questionElement == null) {
            Integer pos = question.getPosition();
            if (pos != null && pos >= 0) {
              try {
                if (questionElements != null && pos < questionElements.size()) {
                  questionElement = questionElements.get(pos);
                } else {
                  int index = pos + 1; // XPath is 1-based
                  questionElement =
                      driver.findElement(By.xpath("(//div[@role='listitem'])[" + index + "]"));
                }
              } catch (Exception ignore) {
              }
            }
          }
        } else {
          // From second section onwards: prefer additionalData.section_index
          Map<String, String> add = question.getAdditionalData();
          String secIdx = add != null ? add.get("section_index") : null;
          if (secIdx != null) {
            try {
              int sIdx = Integer.parseInt(secIdx);
              if (sIdx == sectionIndex) {
                // Use helper first (it knows liIndex/containerXPath/headingNormalized)
                questionElement = formFillingHelper.resolveQuestionElement(driver, "", question);
              }
            } catch (Exception ignore) {
            }
          }
        }
        if (questionElement == null) {
          // Fallback to local resolver within current section
          questionElement = resolveQuestionElementInSection(driver, question, questionElements);
        }
        if (questionElement == null)
          continue;

        boolean ok = formFillingHelper.fillQuestionByType(driver, questionElement, question, option,
            humanLike);
        if (ok) {
          processed++;
          if (humanLike) {
            try {
              Thread.sleep(25 + new Random().nextInt(26));
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
          }
        }
      }
    } catch (Exception e) {
      log.warn("fillCurrentSection error: {}", e.getMessage());
    }
    return processed;
  }

  // Prioritize additionalData (liIndex/containerXPath/headingNormalized, and section_index intent)
  private WebElement resolveQuestionElementInSection(WebDriver driver, Question question,
      List<WebElement> questionElements) {
    try {
      Map<String, String> add = question.getAdditionalData();

      // Priority 1: liIndex within page
      if (add != null && add.get("liIndex") != null) {
        try {
          int idx = Integer.parseInt(add.get("liIndex"));
          List<WebElement> all = driver.findElements(By.cssSelector("div[role='listitem']"));
          if (idx >= 0 && idx < all.size())
            return all.get(idx);
        } catch (Exception ignore) {
        }
      }

      // Priority 2: containerXPath
      if (add != null && add.get("containerXPath") != null
          && !add.get("containerXPath").isBlank()) {
        try {
          return driver.findElement(By.xpath(add.get("containerXPath")));
        } catch (Exception ignore) {
        }
      }

      // Priority 3: headingNormalized
      if (add != null && add.get("headingNormalized") != null
          && !add.get("headingNormalized").isBlank()) {
        try {
          String t = add.get("headingNormalized");
          return driver.findElement(
              By.xpath("//div[@role='listitem'][.//div[@role='heading' and normalize-space()=\""
                  + t.replace("\"", "\\\"") + "\"]]"));
        } catch (Exception ignore) {
        }
      }

      // Fallback: match by visible title within current listitems
      String title = question.getTitle() == null ? "" : question.getTitle();
      for (WebElement el : questionElements) {
        try {
          List<WebElement> headings = el.findElements(By.cssSelector("[role='heading']"));
          if (!headings.isEmpty()) {
            String h = headings.get(0).getText().replace("*", "").trim();
            if (!h.isEmpty() && h.equals(title)) {
              return el;
            }
          }
        } catch (Exception ignore) {
        }
      }
    } catch (Exception e) {
      log.debug("resolveQuestionElementInSection error: {}", e.getMessage());
    }
    return null;
  }

  private void clickNext(WebDriver driver, WebDriverWait wait, boolean humanLike) {
    try {
      WebElement next = wait.until(ExpectedConditions.elementToBeClickable(NEXT_BUTTON));
      if (humanLike) {
        try {
          Thread.sleep(250 + new Random().nextInt(251));
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
      next.click();
      try {
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
      } catch (Exception ignore) {
      }
    } catch (Exception e) {
      log.warn("clickNext failed: {}", e.getMessage());
    }
  }

  private void clickSubmit(WebDriver driver, WebDriverWait wait, boolean humanLike) {
    try {
      WebElement submit = wait.until(ExpectedConditions.elementToBeClickable(SUBMIT_BUTTON));
      if (humanLike) {
        try {
          Thread.sleep(250 + new Random().nextInt(251));
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
      submit.click();
      log.info("Clicked Submit button");

      // Wait for submission confirmation with multiple approaches
      boolean submitConfirmed = false;
      try {
        // Wait for URL change to formResponse (primary indicator)
        WebDriverWait submitWait = new WebDriverWait(driver, Duration.ofSeconds(20));
        submitWait.until(ExpectedConditions.urlContains("formResponse"));
        log.info("Form submitted successfully - URL contains 'formResponse'");
        submitConfirmed = true;
      } catch (Exception e) {
        log.warn("Could not detect form submission via URL change: {}", e.getMessage());

        // Fallback: Wait for submit button to disappear
        try {
          WebDriverWait fallbackWait = new WebDriverWait(driver, Duration.ofSeconds(15));
          fallbackWait.until(ExpectedConditions.invisibilityOfElementLocated(SUBMIT_BUTTON));
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
    } catch (Exception e) {
      log.warn("clickSubmit failed: {}", e.getMessage());
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
        List<WebElement> submitButtons = driver.findElements(SUBMIT_BUTTON);
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
   * Click Next button with enhanced retry logic and section change detection Returns true if
   * section successfully changed, false if stuck in same section
   */
  private boolean clickNextWithSectionChangeDetection(WebDriver driver, WebDriverWait wait,
      int iteration, int sectionIndex) {
    try {
      // CRITICAL: Validate required questions BEFORE attempting to click Next
      ValidationResult validation =
          requiredQuestionAutofillService.validateRequiredQuestions(driver);
      if (!validation.isValid()) {
        log.error("Cannot proceed to next section - Required questions not filled: {}",
            validation.getErrorMessage());
        log.error("Missing required questions: {}", validation.getMissingRequiredQuestions());

        // Try to autofill one more time
        log.info("Attempting final autofill for missing required questions in section {}",
            sectionIndex);
        boolean autofillSuccess = requiredQuestionAutofillService.satisfyRequiredQuestions(driver);

        if (autofillSuccess) {
          // Re-validate after autofill
          validation = requiredQuestionAutofillService.validateRequiredQuestions(driver);
          if (!validation.isValid()) {
            String errorMsg = String.format(
                "FORM FILLING ERROR - Section %d: Cannot proceed due to unfilled required questions: %s",
                sectionIndex, validation.getErrorMessage());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
          }
        } else {
          String errorMsg = String.format(
              "FORM FILLING ERROR - Section %d: Autofill failed and required questions remain unfilled: %s",
              sectionIndex, validation.getErrorMessage());
          log.error(errorMsg);
          throw new RuntimeException(errorMsg);
        }
      }

      // Store current section identifier before clicking
      String currentSectionId = getCurrentSectionIdentifier(driver);
      log.debug("Current section identifier before clicking Next: {}", currentSectionId);

      int clickAttempts = 0;
      int maxClickAttempts = 3;
      boolean nextClickSuccess = false;

      while (!nextClickSuccess && clickAttempts < maxClickAttempts) {
        try {
          WebElement next = driver.findElement(NEXT_BUTTON);

          // Verify button is actually clickable
          if (!next.isDisplayed() || !next.isEnabled()) {
            log.warn("Next button not clickable in section {}", sectionIndex);
            break;
          }

          // Check if button is disabled
          String ariaDisabled = next.getAttribute("aria-disabled");
          if ("true".equals(ariaDisabled)) {
            log.warn(
                "Next button is disabled in section {}, this indicates required questions are not filled",
                sectionIndex);

            // Re-validate to show what's missing
            ValidationResult revalidation =
                requiredQuestionAutofillService.validateRequiredQuestions(driver);
            if (!revalidation.isValid()) {
              String errorMsg = String.format(
                  "FORM FILLING ERROR - Section %d: Next button disabled due to missing required questions: %s",
                  sectionIndex, revalidation.getErrorMessage());
              log.error(errorMsg);
              throw new RuntimeException(errorMsg);
            }
            break;
          }

          next.click();
          nextClickSuccess = true;
          clickAttempts++;
          log.info("Successfully clicked Next button in section {} (attempt {}/{})", sectionIndex,
              clickAttempts, maxClickAttempts);

        } catch (Exception e) {
          clickAttempts++;
          log.warn("Failed to click Next button in section {} (attempt {}/{}): {}", sectionIndex,
              clickAttempts, maxClickAttempts, e.getMessage());

          if (clickAttempts < maxClickAttempts) {
            try {
              Thread.sleep(1000 * clickAttempts); // Exponential backoff
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        }
      }

      if (!nextClickSuccess) {
        log.error("Failed to click Next button in section {} after {} attempts", sectionIndex,
            maxClickAttempts);
        return false;
      }

      // Wait for section change and verify it actually changed
      try {
        Thread.sleep(500); // brief pause to allow DOM update
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }

      try {
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));

        // Check if section actually changed by comparing identifiers
        String newSectionId = getCurrentSectionIdentifier(driver);
        log.debug("New section identifier after clicking Next: {}", newSectionId);

        if (currentSectionId.equals(newSectionId)) {
          log.error(
              "Section did not change after clicking Next button in section index {} (attempt {}/3)",
              sectionIndex, clickAttempts);

          // If this is the 3rd attempt and still no change, return false
          if (clickAttempts >= 3) {
            log.error(
                "Failed to change section after 3 attempts in section index {}, stopping navigation",
                sectionIndex);
            return false;
          }

          // Try again
          return clickNextWithSectionChangeDetection(driver, wait, iteration, sectionIndex);
        } else {
          log.info("Section successfully changed from {} to {} in section index {}",
              currentSectionId, newSectionId, sectionIndex);
          return true;
        }

      } catch (Exception e) {
        log.warn("Failed to verify section change: {}", e.getMessage());
        // Assume change was successful if we can't verify
        return true;
      }

    } catch (Exception e) {
      log.error("Error in clickNextWithSectionChangeDetection for section {}: {}", sectionIndex,
          e.getMessage());
      return false;
    }
  }

  /**
   * Get current section identifier for change detection
   */
  private String getCurrentSectionIdentifier(WebDriver driver) {
    try {
      String currentUrl = driver.getCurrentUrl();

      // Get current page source hash for uniqueness
      String pageSource = driver.getPageSource();
      int pageSourceHash = pageSource.hashCode();

      // Get question count
      List<WebElement> questionElements =
          driver.findElements(By.cssSelector("div[role='listitem']"));
      int questionCount = questionElements.size();

      // Create unique identifier
      String identifier = currentUrl + "|" + pageSourceHash + "|" + questionCount;
      return String.valueOf(identifier.hashCode());

    } catch (Exception e) {
      log.debug("Error getting section identifier: {}", e.getMessage());
      return String.valueOf(System.currentTimeMillis()); // Fallback
    }
  }

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

      log.debug(
          "Section question check: questionElements={}, textInputs={}, radioGroups={}, checkboxes={}, listboxes={}, hasQuestions={}",
          questionElements.size(), textInputs.size(), radioGroups.size(), checkboxes.size(),
          listboxes.size(), hasQuestions);

      return hasQuestions;
    } catch (Exception e) {
      log.warn("Error checking for questions in current section: {}", e.getMessage());
      return false; // Assume no questions if an error occurs
    }
  }
}


