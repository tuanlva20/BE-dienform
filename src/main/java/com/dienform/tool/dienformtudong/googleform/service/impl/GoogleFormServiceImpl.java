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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
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
import io.github.bonigarcia.wdm.WebDriverManager;
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
        FillRequest fillRequest =
                fillRequestRepository.findByIdWithFetchForm(fillRequestId).orElseThrow(
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
        List<Map<Question, QuestionOption>> executionPlans =
                createExecutionPlans(distributionsByQuestion, fillRequest.getSurveyCount());

        // Initialize counters
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        Random random = new Random();

        // Execute form filling tasks
        for (Map<Question, QuestionOption> plan : executionPlans) {
            int delayMillis = 500 + random.nextInt(1501); // 0.5-2s
            try {
                // Add delay before starting each task
                Thread.sleep(delayMillis);

                futures.add(CompletableFuture.runAsync(() -> {
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
                }, executor));

                log.debug("Scheduled task with delay of {}ms", delayMillis);
            } catch (InterruptedException e) {
                log.error("Task scheduling was interrupted", e);
                Thread.currentThread().interrupt();
            }
        }

        // Shut down executor and wait for completion
        CompletableFuture<Void> allTasks =
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        try {
            allTasks.get();
            executor.awaitTermination(180, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Form filling execution was interrupted", e);
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }

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

    @Override
    public String extractTitleFromFormLink(String formLink) {
        try {
            // No need to convert to public URL since we're receiving it directly
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20)).build();

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(formLink)).header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .GET().build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Failed to fetch Google Form. Status code: {}", response.statusCode());
                return null;
            }

            String htmlContent = response.body();
            if (htmlContent == null || htmlContent.isEmpty()) {
                log.error("Empty HTML content received from Google Form URL");
                return null;
            }

            // Parse HTML and extract the title element
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(htmlContent);
            org.jsoup.nodes.Element heading = doc.selectFirst("[role=heading][aria-level=1]");

            if (heading != null) {
                return heading.text();
            }

            log.warn("No heading element found with role=heading and aria-level=1");
            return null;

        } catch (Exception e) {
            log.error("Error extracting form title from link: {}", e.getMessage(), e);
            return null;
        }
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

                // Đảm bảo câu hỏi bắt buộc phải được điền
                if (question.getRequired() != null && question.getRequired()) {
                    // Handle text questions
                    if ("text".equalsIgnoreCase(question.getType())) {
                        // Lọc ra các distribution có valueString không null và không rỗng
                        List<AnswerDistribution> textDistributions = questionDistributions.stream()
                                .filter(dist -> dist.getValueString() != null
                                        && !dist.getValueString().isEmpty())
                                .collect(Collectors.toList());

                        // Tạo QuestionOption để lưu giá trị text
                        QuestionOption textOption = new QuestionOption();
                        textOption.setQuestion(question);

                        // Nếu có ít nhất 1 distribution với valueString, ưu tiên sử dụng dữ liệu từ
                        // người dùng
                        if (textDistributions.size() > 0) {
                            // Chọn một valueString ngẫu nhiên từ danh sách
                            int randomIndex = new Random().nextInt(textDistributions.size());
                            String userText = textDistributions.get(randomIndex).getValueString();
                            textOption.setText(userText);
                        } else {
                            // Thay vì dùng placeholder, sử dụng random data từ TestDataEnum
                            String textValue = generateTextByQuestionType(question.getTitle());
                            textOption.setText(textValue);
                            log.debug("Using generated text from TestDataEnum: {}", textValue);
                        }

                        plan.put(question, textOption);
                    } else {
                        // Handle non-text questions (radio, checkbox, etc.)
                        QuestionOption selectedOption =
                                selectOptionBasedOnDistribution(questionDistributions);
                        if (selectedOption != null) {
                            plan.put(question, selectedOption);
                        }
                    }
                }
                // Nếu không bắt buộc, vẫn xử lý như trước
                else {
                    // Handle text questions
                    if ("text".equalsIgnoreCase(question.getType())) {
                        // Lọc ra các distribution có valueString không null và không rỗng
                        List<AnswerDistribution> textDistributions = questionDistributions.stream()
                                .filter(dist -> dist.getValueString() != null
                                        && !dist.getValueString().isEmpty())
                                .collect(Collectors.toList());

                        // Tạo QuestionOption để lưu giá trị text
                        QuestionOption textOption = new QuestionOption();
                        textOption.setQuestion(question);

                        // Nếu có ít nhất 1 distribution với valueString, ưu tiên sử dụng dữ liệu từ
                        // người dùng
                        if (textDistributions.size() > 0) {
                            // Chọn một valueString ngẫu nhiên từ danh sách
                            int randomIndex = new Random().nextInt(textDistributions.size());
                            String userText = textDistributions.get(randomIndex).getValueString();
                            textOption.setText(userText);
                        } else {
                            // Thay vì dùng placeholder, sử dụng random data từ TestDataEnum
                            String textValue = generateTextByQuestionType(question.getTitle());
                            textOption.setText(textValue);
                            log.debug("Using generated text from TestDataEnum: {}", textValue);
                        }

                        plan.put(question, textOption);
                    } else {
                        // Handle non-text questions (radio, checkbox, etc.)
                        QuestionOption selectedOption =
                                selectOptionBasedOnDistribution(questionDistributions);
                        if (selectedOption != null) {
                            plan.put(question, selectedOption);
                        }
                    }
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
                log.warn(
                        "WebDriverManager failed to setup ChromeDriver: {}. Trying alternative methods...",
                        e.getMessage());
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
            List<WebElement> questionElements =
                    driver.findElements(By.xpath("//div[@role='listitem']"));
            for (Map.Entry<Question, QuestionOption> entry : selections.entrySet()) {
                Question question = entry.getKey();
                QuestionOption option = entry.getValue();

                // Find the question element have <div role="listitem"> and have containt
                // question.getTitle()
                final WebElement questionElement =
                        findQuestionElement(question.getTitle(), questionElements);

                if (questionElement == null) {
                    log.warn("Question not found: {}", question.getTitle());
                    continue;
                }
                log.info("Filling question: {} (type: {})", question.getTitle(),
                        question.getType());

                // Fill the question based on its type
                switch (question.getType().toLowerCase()) {
                    case "radio":
                        fillRadioQuestion(driver, questionElement, option.getText());
                        break;
                    case "checkbox":
                        fillCheckboxQuestion(driver, questionElement, option.getText());
                        break;
                    case "text":
                        fillTextQuestion(driver, questionElement, question.getTitle(), selections);
                        break;
                    case "combobox":
                        fillComboboxQuestion(driver, questionElement, question.getTitle(),
                                option.getText());
                        break;
                    default:
                        log.warn("Unsupported question type: {}", question.getType());
                        break;
                }

                // Add human-like delay between questions
                if (true) {
                    int delay = (int) (Math.random() * 1000 + 1000); // 1-2 seconds
                    Thread.sleep(delay);
                }
            }

            // If auto-submit is enabled, click the submit button
            if (autoSubmitEnabled) {
                try {

                    WebElement submitButton = wait.until(ExpectedConditions.elementToBeClickable(
                            By.xpath("//div[@role='button' and @aria-label='Submit']")));

                    if (humanLike) {
                        Thread.sleep((int) (Math.random() * 1000 + 1000)); // 1-2 seconds before
                                                                           // submit
                    }

                    submitButton.click();

                    // Wait for submission confirmation
                    wait.until(ExpectedConditions.urlContains("formResponse"));

                    log.info("Form submitted successfully");
                } catch (NoSuchElementException e) {
                    log.error("Submit form unsuccessfully: {}", e.getMessage());
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
        String[] possiblePaths = {"/usr/local/bin/chromedriver", "/usr/bin/chromedriver",
                "/opt/homebrew/bin/chromedriver",
                System.getProperty("user.home") + "/chromedriver"};

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

        log.warn(
                "Could not find ChromeDriver in common locations. Make sure ChromeDriver is installed and in PATH");
    }

    /**
     * Find a question element by its title
     *
     * @param questionTitle Title of the question to find
     * @return WebElement for the question container
     */
    private WebElement findQuestionElement(String questionTitle,
            List<WebElement> questionElements) {
        try {
            // Find the one with matching text
            for (WebElement element : questionElements) {
                if (element.getText().replace("*", "").trim()
                        .contains(questionTitle.replace("*", "").trim())) {
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
            List<WebElement> optionLabels =
                    questionElement.findElements(By.cssSelector("[role=radio]"));

            // Click on the option that matches the text
            for (WebElement label : optionLabels) {
                if (label.getAttribute("aria-label").trim().equals(optionText)
                        || optionText.contains(label.getAttribute("aria-label").trim())) {
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
            List<WebElement> checkboxOptions =
                    questionElement.findElements(By.cssSelector("[role=checkbox]"));

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
     * @param selections Map chứa các câu hỏi và option được chọn
     */
    private void fillTextQuestion(WebDriver driver, WebElement questionElement,
            String questionTitle, Map<Question, QuestionOption> selections) {
        try {
            log.info("Processing text question: {}", questionTitle);

            // Try multiple possible selectors to find text inputs
            List<WebElement> textInputs = questionElement.findElements(By.xpath(
                    ".//input[@type='text'] | .//textarea | .//input[contains(@class, 'quantumWizTextinputPaperinputInput')]"));

            if (textInputs.isEmpty()) {
                // Try more generic approach if specific selectors fail
                textInputs = questionElement.findElements(By.tagName("input"));
                if (textInputs.isEmpty()) {
                    textInputs = questionElement.findElements(By.tagName("textarea"));
                }
            }

            if (textInputs.isEmpty()) {
                log.error("No text input found for question: {}", questionTitle);
                return;
            }

            WebElement textInput = textInputs.get(0);
            String textToEnter;

            // Tìm option cho câu hỏi này trong selections
            for (Map.Entry<Question, QuestionOption> entry : selections.entrySet()) {
                if (entry.getKey().getTitle().equals(questionTitle)) {
                    QuestionOption option = entry.getValue();
                    // Chỉ kiểm tra option có giá trị text hay không
                    if (option != null && option.getText() != null && !option.getText().isEmpty()) {
                        textToEnter = option.getText();
                        log.info("Using predefined text: {}", textToEnter);

                        // Click vào trường văn bản để focus
                        textInput.click();
                        // Xóa nội dung hiện có
                        textInput.clear();
                        // Nhập văn bản
                        textInput.sendKeys(textToEnter);
                        return;
                    }
                    break;
                }
            }

            // Nếu không có text từ người dùng, tạo text ngẫu nhiên phù hợp
            String textValue = generateTextByQuestionType(questionTitle);

            // Click on the text field first to ensure focus
            textInput.click();
            // Clear existing text if any
            textInput.clear();
            // Enter the text
            textInput.sendKeys(textValue);
            log.info("Entered generated text: {}", textValue);
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
    private void fillComboboxQuestion(WebDriver driver, WebElement questionElement,
            String questionTitle, String optionText) {
        try {
            List<WebElement> comboboxs =
                    questionElement.findElements(By.xpath("//div[@role='listbox']"));
            for (WebElement combobox : comboboxs) {
                WebElement parentElement =
                        combobox.findElement(By.xpath("./ancestor::div[@role='listitem']"));
                if (parentElement.getText().contains(questionTitle)
                        || questionTitle.contains(parentElement.getText())) {
                    // wait 500ms before click
                    Thread.sleep(200);
                    combobox.click();

                    WebDriverWait wait =
                            new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));
                    wait.until(webDriver -> !parentElement
                            .findElements(
                                    By.xpath(".//div[@role='listbox' and @aria-expanded='true']"))
                            .isEmpty());
                    break;
                }
            }

            List<WebElement> listboxElements =
                    driver.findElements(By.xpath("//div[@role='listbox']"));
            for (WebElement listBoxElement : listboxElements) {
                if (!listBoxElement.getText().trim().contains(optionText)) {
                    continue;
                }
                List<WebElement> options =
                        listBoxElement.findElements(By.xpath(".//div[@role='option']"));
                for (WebElement option : options) {
                    if (option.getText().trim().contains(optionText)
                            || optionText.trim().contains(option.getText())) {
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

    /**
     * Generate appropriate text based on question type/content
     *
     * @param questionTitle The title of the question to analyze
     * @return Generated text appropriate for the question
     */
    private String generateTextByQuestionType(String questionTitle) {
        String questionLower = questionTitle.toLowerCase();

        if (questionLower.contains("email")) {
            return TestDataEnum.getRandomEmail();
        } else if (questionLower.contains("tên") || questionLower.contains("họ")
                || questionLower.contains("name")) {
            return TestDataEnum.getRandomName();
        } else if (questionLower.contains("số điện thoại") || questionLower.contains("phone")) {
            return TestDataEnum.getRandomPhoneNumber();
        } else if (questionLower.contains("địa chỉ") || questionLower.contains("address")) {
            return TestDataEnum.getRandomAddress();
        } else {
            return TestDataEnum.getRandomFeedback();
        }
    }
}

