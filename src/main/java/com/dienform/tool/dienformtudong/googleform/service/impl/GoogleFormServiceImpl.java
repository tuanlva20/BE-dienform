package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.dienform.tool.dienformtudong.surveyexecution.entity.SurveyExecution;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.hibernate.SessionException;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.exception.ResourceNotFoundException;
import com.dienform.common.util.ArrayUtils;
import com.dienform.common.util.Constants;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.answerdistribution.repository.AnswerDistributionRepository;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.enums.FormStatusEnum;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.googleform.dto.FormSubmissionRequest;
import com.dienform.tool.dienformtudong.googleform.dto.FormSubmissionResponse;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.googleform.util.GoogleFormParser;
import com.dienform.tool.dienformtudong.googleform.util.GoogleFormParser.ExtractedQuestion;
import com.dienform.tool.dienformtudong.googleform.util.TestDataEnum;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SesstionExecution;
import com.dienform.tool.dienformtudong.surveyexecution.repository.SessionExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of the GoogleFormService interface
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleFormServiceImpl implements GoogleFormService {

    private final GoogleFormParser googleFormParser;
    private final FillRequestRepository fillRequestRepository;
    private final FormRepository formRepository;
    private final AnswerDistributionRepository answerDistributionRepository;
    private final SessionExecutionRepository sessionExecutionRepository;

    // In-memory cache of form questions (URL -> Questions)
    private final Map<String, List<ExtractedQuestion>> formQuestionsCache =
            new ConcurrentHashMap<>();

    @Value("${google.form.auto-submit:true}")
    private boolean autoSubmitEnabled;

    @Value("${google.form.thread-pool-size:5}")
    private int threadPoolSize;

    @Value("${google.form.timeout-seconds:30}")
    private int timeoutSeconds;

    @Override
    public FormSubmissionResponse submitForm(FormSubmissionRequest request) {
        // TODO: Implement form submission logic
        return null;
    }

    @Override
    public List<ExtractedQuestion> readGoogleForm(String formUrl) {
        try {
            log.info("Fetching Google Form from URL: {}", formUrl);

            // Create HTTP client with reasonable timeout
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20)).build();

            // Prepare the request
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(formUrl)).header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .GET().build();

            // Execute the request and get the response
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Failed to fetch Google Form. Status code: {}", response.statusCode());
                return Collections.emptyList();
            }

            String htmlContent = response.body();

            if (htmlContent == null || htmlContent.isEmpty()) {
                log.error("Empty HTML content received from Google Form URL");
                return Collections.emptyList();
            }

            // Parse question from URL Google Form
            List<ExtractedQuestion> extractedQuestions =
                    googleFormParser.extractQuestionsFromHtml(htmlContent);

            if (ArrayUtils.isEmpty(extractedQuestions)) {
                log.warn("No questions extracted from the form at URL: {}", formUrl);
                return Collections.emptyList();
            }

            log.info("Successfully extracted {} questions from Google Form",
                    extractedQuestions.size());
            return extractedQuestions;

        } catch (IOException e) {
            log.error("IO exception occurred while fetching Google Form: {}", e.getMessage(), e);
            return Collections.emptyList();
        } catch (InterruptedException e) {
            log.error("Thread was interrupted while fetching Google Form: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Unexpected error occurred while processing Google Form: {}", e.getMessage(),
                    e);
            return Collections.emptyList();
        }
    }

    @Override
    @Transactional
    public int fillForm(UUID fillRequestId) {
        log.info("Starting automated form filling for request ID: {}", fillRequestId);

        // Find the fill request
        FillRequest fillRequest = fillRequestRepository.findByIdWithFetchForm(fillRequestId).orElseThrow(
                () -> new ResourceNotFoundException("Fill Request", "id", fillRequestId));

        // Find the form
        Form form = formRepository.findById(fillRequest.getForm().getId()).orElseThrow(
                () -> new ResourceNotFoundException("Form", "id", fillRequest.getForm().getId()));

        String link = form.getEditLink();
        if (link == null || link.isEmpty()) {
            log.error("Form edit link is empty for form ID: {}", form.getId());
            return 0;
        }

        // Find answer distributions
        List<AnswerDistribution> distributions = fillRequest.getAnswerDistributions();
        if (ArrayUtils.isEmpty(distributions)) {
            log.error("No answer distributions found for request ID: {}", fillRequestId);
            return 0;
        }

        // Create a record in fill form sessionExecute
        SesstionExecution sessionExecute = SesstionExecution.builder().formId(form.getId())
                .fillRequestId(fillRequestId).startTime(LocalDateTime.now())
                .totalExecutions(fillRequest.getSurveyCount()).successfulExecutions(0)
                .failedExecutions(0).status(FormStatusEnum.PROCESSING).build();

        sessionExecute = sessionExecutionRepository.save(sessionExecute);

        // Group distributions by question
        Map<Question, List<AnswerDistribution>> distributionsByQuestion = distributions.stream()
                .collect(Collectors.groupingBy(AnswerDistribution::getQuestion));

        // Create execution plan based on percentages
        List<Map<Question, QuestionOption>> executionPlans = createExecutionPlans(distributionsByQuestion, fillRequest.getSurveyCount());

        // Initialize counters
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // Execute form filling tasks
        for (Map<Question, QuestionOption> plan : executionPlans) {
//            executor.submit(() -> {
                try {
                    boolean result = executeFormFill(link, plan, fillRequest.isHumanLike());
                    if (result) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Error during form filling: {}", e.getMessage(), e);
                    failCount.incrementAndGet();
                }
//            });
        }

        // Shut down executor and wait for completion
//        executor.shutdown();
//        try {
//            executor.awaitTermination(1, TimeUnit.HOURS);
//        } catch (InterruptedException e) {
//            log.error("Form filling execution was interrupted", e);
//            Thread.currentThread().interrupt();
//        }

        // Update sessionExecute record
        sessionExecute.setEndTime(LocalDateTime.now());
        sessionExecute.setSuccessfulExecutions(successCount.get());
        sessionExecute.setFailedExecutions(failCount.get());
        sessionExecute.setStatus(FormStatusEnum.COMPLETED);
        sessionExecutionRepository.save(sessionExecute);

        // Update fill request status
        fillRequest.setStatus(Constants.FILL_REQUEST_STATUS_COMPLETED);
        fillRequestRepository.save(fillRequest);

        log.info("Form filling completed for request ID: {}. Success: {}, Failed: {}",
                fillRequestId, successCount.get(), failCount.get());

        return successCount.get();
    }

    /**
     * Create execution plans based on the distribution percentages
     *
     * @param distributionsByQuestion Distributions grouped by question
     * @param serveyCount Total number of forms to fill
     * @return List of execution plans (each plan is a map of question to selected option)
     */
    private List<Map<Question, QuestionOption>> createExecutionPlans(
            Map<Question, List<AnswerDistribution>> distributionsByQuestion, int serveyCount) {

        List<Map<Question, QuestionOption>> plans = new ArrayList<>(serveyCount);

        for (int i = 0; i < serveyCount; i++) {
            Map<Question, QuestionOption> plan = new HashMap<>();

            // For each question, select an option based on distributions
            for (Map.Entry<Question, List<AnswerDistribution>> entry : distributionsByQuestion
                    .entrySet()) {
                Question question = entry.getKey();
                List<AnswerDistribution> questionDistributions = entry.getValue();

                // Select an option based on distribution percentages
                QuestionOption selectedOption =
                        selectOptionBasedOnDistribution(questionDistributions);
                if (selectedOption != null) {
                    plan.put(question, selectedOption);
                }
            }

            plans.add(plan);
        }

        return plans;
    }

    /**
     * Select an option based on the distribution percentages
     *
     * @param distributions List of answer distributions
     * @return Selected question option
     */
    private QuestionOption selectOptionBasedOnDistribution(List<AnswerDistribution> distributions) {
        if (distributions == null || distributions.isEmpty()) {
            return null;
        }

        // Calculate total percentage
        int totalPercentage =
                distributions.stream().mapToInt(AnswerDistribution::getPercentage).sum();

        if (totalPercentage <= 0) {
            return null;
        }

        // Generate a random value between 0 and total percentage
        int randomValue = (int) (Math.random() * totalPercentage);

        // Select option based on cumulative percentage
        int cumulativePercentage = 0;
        for (AnswerDistribution distribution : distributions) {
            cumulativePercentage += distribution.getPercentage();
            if (randomValue < cumulativePercentage) {
                return distribution.getOption();
            }
        }

        // Default to the first option if something went wrong
        return distributions.get(0).getOption();
    }

    /**
     * Execute a form fill using Selenium
     *
     * @param formUrl The URL of the form to fill
     * @param selections Map of questions to selected options
     * @param humanLike Whether to simulate human-like behavior
     * @return True if successful, false otherwise
     */
    private boolean executeFormFill(String formUrl, Map<Question, QuestionOption> selections,
            boolean humanLike) {
        WebDriver driver = null;

        try {
            // Setup Chrome driver with WebDriverManager
            try {
                log.info("Setting up ChromeDriver using WebDriverManager...");
                WebDriverManager.chromedriver().setup();
            } catch (Exception e) {
                log.warn("WebDriverManager failed to setup ChromeDriver: {}. Trying alternative methods...", e.getMessage());
                setupChromeDriverManually();
            }

            // Setup Chrome options
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--incognito");
            options.addArguments("--window-size=1366,768");

            // Add additional options to improve stability
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--remote-allow-origins=*");

            // If not human-like, run in headless mode
            if (!humanLike) {
                options.addArguments("--headless=new");
            }

            log.info("Creating ChromeDriver instance...");
            driver = new ChromeDriver(options);
            log.info("ChromeDriver created successfully");

            // Open the form
            driver.get(formUrl);
            log.info("Navigated to form URL: {}", formUrl);

            // Create wait object for waiting for elements
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));

            // Iterate through the selections and fill the form
            List<WebElement> questionElements = driver.findElements(By.xpath("//div[@role='listitem']"));
            for (Map.Entry<Question, QuestionOption> entry : selections.entrySet()) {
                Question question = entry.getKey();
                QuestionOption option = entry.getValue();

                // Find the question element have <div role="listitem"> and have containt question.getTitle()
                final WebElement questionElement = findQuestionElement(question.getTitle(), questionElements);

                if (questionElement == null) {
                    log.warn("Question not found: {}", question.getTitle());
                    continue;
                }
                log.warn("Filling question: {}", question.getTitle());

                // Fill the question based on its type
                switch (question.getType()) {
                    case "radio":
                        fillRadioQuestion(driver, questionElement, option.getText());
                        break;
                    case "checkbox":
                        fillCheckboxQuestion(driver, questionElement, option.getText());
                        break;
                    case "text":
                        fillTextQuestion(driver, questionElement, question.getTitle());
                        break;
                    case "combobox":
                        fillComboboxQuestion(driver, questionElement, question.getTitle(), option.getText());
                        break;
                    default:
                        log.warn("Unsupported question type: {}", question.getType());
                        break;
                }

                // Add human-like delay between questions
                if (true) {
                    int delay = (int) (Math.random() * 1000 + 2000); // 1-3 seconds
                    Thread.sleep(delay);
                }
            }

            // If auto-submit is enabled, click the submit button
            if (autoSubmitEnabled) {
                try {
                    WebElement submitButton = wait.until(ExpectedConditions.elementToBeClickable(
                            By.cssSelector(".freebirdFormviewerViewNavigationSubmitButton")));

                    if (humanLike) {
                        Thread.sleep((int) (Math.random() * 3000 + 2000)); // 2-5 seconds before submit
                    }

                    submitButton.click();

                    // Wait for submission confirmation
                    wait.until(ExpectedConditions.urlContains("formResponse"));

                    log.info("Form submitted successfully");
                } catch (NoSuchElementException e) {
                    log.error("Submit button not found: {}", e.getMessage());
                    return false;
                }
            } else {
                log.info("Form filled successfully, auto-submit disabled");
            }

            return true;

        } catch (Exception e) {
            log.error("Error filling form: {}", e.getMessage(), e);
            return false;
        } finally {
            // Close the browser window if driver was initialized
            if (driver != null) {
                try {
                    driver.quit();
                    log.info("WebDriver closed successfully");
                } catch (Exception e) {
                    log.error("Error closing WebDriver: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Attempt to setup ChromeDriver manually if WebDriverManager fails
     */
    private void setupChromeDriverManually() {
        log.info("Attempting to setup ChromeDriver manually...");

        // Try different possible paths for ChromeDriver
        String[] possiblePaths = {
            "/usr/local/bin/chromedriver",
            "/usr/bin/chromedriver",
            "/opt/homebrew/bin/chromedriver",
            System.getProperty("user.home") + "/chromedriver"
        };

        for (String path : possiblePaths) {
            File chromeDriver = new File(path);
            if (chromeDriver.exists() && chromeDriver.canExecute()) {
                System.setProperty("webdriver.chrome.driver", path);
                log.info("Found ChromeDriver at: {}", path);
                return;
            }
        }

        // Try to use the current directory
        String currentDir = Paths.get("").toAbsolutePath().toString();
        String chromeDriverPath = currentDir + "/chromedriver";
        File chromeDriver = new File(chromeDriverPath);
        if (chromeDriver.exists() && chromeDriver.canExecute()) {
            System.setProperty("webdriver.chrome.driver", chromeDriverPath);
            log.info("Found ChromeDriver in current directory: {}", chromeDriverPath);
            return;
        }

        log.warn("Could not find ChromeDriver in common locations. Make sure ChromeDriver is installed and in PATH");
    }

    /**
     * Find a question element by its title
     *
     * @param questionTitle Title of the question to find
     * @return WebElement for the question container
     */
    private WebElement findQuestionElement(String questionTitle, List<WebElement> questionElements) {
      try {
        // Find the one with matching text
        for (WebElement element : questionElements) {
          if (element.getText().trim().contains(questionTitle)) {
            // Return the parent container
            return element;
          }
        }
          return null;
      } catch (Exception e) {
        log.error("Error finding question element: {}", e.getMessage(), e);
        return null;
      }
    }

    /**
     * Fill a radio button question
     *
     * @param driver WebDriver instance
     * @param questionElement Question container element
     * @param optionText Text of the option to select
     */
    private void fillRadioQuestion(WebDriver driver, WebElement questionElement,
            String optionText) {
        try {
            // Find all option labels within the question container
            List<WebElement> optionLabels = questionElement.findElements(By.cssSelector("[role=radio]"));

            // Click on the option that matches the text
            for (WebElement label : optionLabels) {
                if (label.getAttribute("aria-label").trim().equals(optionText) ||
                    optionText.contains(label.getAttribute("aria-label").trim())) {
                  label.click();
                  log.debug("Selected radio option: {}", optionText);
                  return;
                }
            }

            // If exact match not found, try contains
            for (WebElement label : optionLabels) {
                if (label.getText().contains(optionText)) {
                    label.click();
                    log.debug("Selected radio option (partial match): {}", optionText);
                    return;
                }
            }

            log.warn("Radio option not found: {}", optionText);

        } catch (Exception e) {
            log.error("Error filling radio question: {}", e.getMessage(), e);
        }
    }

    /**
     * Fill a checkbox question
     *
     * @param driver WebDriver instance
     * @param questionElement Question container element
     * @param optionText Text of the option to check
     */
    private void fillCheckboxQuestion(WebDriver driver, WebElement questionElement,
            String optionText) {
        try {
            // Find all checkbox options within the question container
            List<WebElement> checkboxOptions = questionElement.findElements(By.cssSelector("[role=checkbox]"));

            // Click on the checkbox that matches the text
            for (WebElement checkbox : checkboxOptions) {
                if (checkbox.getAttribute("aria-label").trim().equals(optionText)) {
                    checkbox.click();
                    log.debug("Checked checkbox option: {}", optionText);
                    return;
                }
            }

            // If exact match not found, try contains
            for (WebElement checkbox : checkboxOptions) {
                if (checkbox.getText().contains(optionText)) {
                    checkbox.click();
                    log.debug("Checked checkbox option (partial match): {}", optionText);
                    return;
                }
            }

            log.warn("Checkbox option not found: {}", optionText);

        } catch (Exception e) {
            log.error("Error filling checkbox question: {}", e.getMessage(), e);
        }
    }

    /**
     * Fill a text input question
     *
     * @param driver WebDriver instance
     * @param questionElement Question container element
     * @param questionTitle Title of the question to determine text input type
     */
    private void fillTextQuestion(WebDriver driver, WebElement questionElement,
            String questionTitle) {
        try {
            // Find the text input element
//            WebElement textInput = questionElement.findElement(By.cssSelector("input[type=text], textarea"));
          WebElement textInput = questionElement.findElement(By.xpath(".//input[@type='text'] | .//textarea"));
            // Determine what kind of text to enter based on the question title
            String textToEnter;

            if (questionTitle.toLowerCase().contains("email")) {
                textToEnter = TestDataEnum.getRandomEmail();
            } else if (questionTitle.toLowerCase().contains("tên")
                    || questionTitle.toLowerCase().contains("họ")
                    || questionTitle.toLowerCase().contains("name")) {
                textToEnter = TestDataEnum.getRandomName();
            } else {
                textToEnter = TestDataEnum.getRandomFeedback();
            }

            // Enter the text
            textInput.sendKeys(textToEnter);
            log.debug("Entered text: {}", textToEnter);

        } catch (Exception e) {
            log.error("Error filling text question: {}", e.getMessage(), e);
        }
    }

    /**
     * Fill a combobox/dropdown question
     *
     * @param driver WebDriver instance
     * @param questionElement Question container element
     * @param optionText Text of the option to select
     */
    private void fillComboboxQuestion(WebDriver driver, WebElement questionElement, String questionTitle,
            String optionText) {
        try {
          List<WebElement> comboboxs = questionElement.findElements(By.xpath("//div[@role='listbox']"));
          for (WebElement combobox : comboboxs) {
            WebElement parentElement = combobox.findElement(By.xpath("./ancestor::div[@role='listitem']"));
            if (parentElement.getText().contains(questionTitle) || questionTitle.contains(parentElement.getText())) {
              //wait 500ms before click
              Thread.sleep(200);
              combobox.click();

              WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));
              wait.until(webDriver -> !parentElement.findElements(By.xpath(".//div[@role='listbox' and @aria-expanded='true']")).isEmpty());
              break;
            }
          }

          List<WebElement> listboxElements = driver.findElements(By.xpath("//div[@role='listbox']"));
          for (WebElement listBoxElement : listboxElements) {
            if (!listBoxElement.getText().trim().contains(optionText)) {
              continue;
            }
            List<WebElement> options = listBoxElement.findElements(By.xpath(".//div[@role='option']"));
            for (WebElement option : options) {
              if (option.getText().trim().contains(optionText) || optionText.trim().contains(option.getText())) {
                  option.click();
                  log.debug("Selected combobox option: {}", optionText);
                  return;
                }
            }
          }
          log.warn("Combobox option not found: {}", optionText);
          // close
          questionElement.click();
        } catch (Exception e) {
          log.error("Error filling combobox question: {}", e.getMessage(), e);
        }
    }
}
