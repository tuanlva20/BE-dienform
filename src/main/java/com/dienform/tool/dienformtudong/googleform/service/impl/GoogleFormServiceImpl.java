package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
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

    @Value("${google.form.timeout-seconds:180}")
    private int timeoutSeconds;

    @Value("${google.form.headless:true}")
    private boolean headless;

    // Performance optimization: Cache question elements to avoid repeated DOM queries
    private final Map<String, List<WebElement>> questionElementsCache = new ConcurrentHashMap<>();

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
            updateFillRequestStatus(fillRequest, Constants.FILL_REQUEST_STATUS_FAILED);
            return 0;
        }

        // Find answer distributions
        List<AnswerDistribution> distributions = fillRequest.getAnswerDistributions();
        if (ArrayUtils.isEmpty(distributions)) {
            log.error("No answer distributions found for request ID: {}", fillRequestId);
            updateFillRequestStatus(fillRequest, Constants.FILL_REQUEST_STATUS_FAILED);
            return 0;
        }

        // Create a record in fill form sessionExecute
        final SesstionExecution sessionExecute = SesstionExecution.builder().formId(form.getId())
                .fillRequestId(fillRequestId).startTime(LocalDateTime.now())
                .totalExecutions(fillRequest.getSurveyCount()).successfulExecutions(0)
                .failedExecutions(0).status(FormStatusEnum.PROCESSING).build();

        sessionExecutionRepository.save(sessionExecute);

        // Update request status to RUNNING
        updateFillRequestStatus(fillRequest, Constants.FILL_REQUEST_STATUS_RUNNING);

        // Group distributions by question
        Map<Question, List<AnswerDistribution>> distributionsByQuestion = distributions.stream()
                .collect(Collectors.groupingBy(AnswerDistribution::getQuestion));

        // Create execution plan based on percentages
        List<Map<Question, QuestionOption>> executionPlans =
                createExecutionPlans(distributionsByQuestion, fillRequest.getSurveyCount());

        // Initialize counters
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger totalProcessed = new AtomicInteger(0);

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
                    boolean result = executeFormFill(link, plan, fillRequest.isHumanLike());
                    if (result) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                }, executor));

                log.debug("Scheduled task with delay of {}ms", delayMillis);
            } catch (Exception e) {
                log.error("Error during form filling: {}", e.getMessage(), e);
                failCount.incrementAndGet();
            } finally {
                // Update progress after each task
                int processed = totalProcessed.incrementAndGet();
                if (processed == fillRequest.getSurveyCount()) {
                    // All tasks completed, update final status
                    updateFinalStatus(fillRequest, sessionExecute, successCount.get(),
                            failCount.get());
                }
            }
        }

        // Shut down executor and wait for completion
        CompletableFuture<Void> allTasks =
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        try {
            // Wait for all tasks with timeout
            allTasks.get(300, TimeUnit.SECONDS); // Increased timeout to 300 seconds (5 minutes)
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Form filling execution was interrupted or timed out", e);
            updateFillRequestStatus(fillRequest, Constants.FILL_REQUEST_STATUS_FAILED);
            return 0;
        } finally {
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }

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
     * Submit form data using browser automation
     */
    @Override
    public boolean submitFormWithBrowser(String formUrl, Map<String, String> formData) {
        try {
            // Convert formData to Map<Question, QuestionOption>
            Map<Question, QuestionOption> selections = new HashMap<>();
            for (Map.Entry<String, String> entry : formData.entrySet()) {
                Question question = new Question();
                question.setId(UUID.fromString(entry.getKey()));

                QuestionOption option = new QuestionOption();
                option.setText(entry.getValue());
                option.setQuestion(question);

                selections.put(question, option);
            }

            // Use existing executeFormFill method
            return executeFormFill(formUrl, selections, true);
        } catch (Exception e) {
            log.error("Error submitting form: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Update fill request status with transaction
     */
    @Transactional
    protected void updateFillRequestStatus(FillRequest fillRequest, String status) {
        try {
            // Reload fill request to ensure we have latest state
            FillRequest current = fillRequestRepository.findById(fillRequest.getId()).orElseThrow(
                    () -> new ResourceNotFoundException("Fill Request", "id", fillRequest.getId()));

            current.setStatus(status);
            fillRequestRepository.save(current);
            log.info("Updated fill request {} status to: {}", current.getId(), status);
        } catch (Exception e) {
            log.error("Failed to update fill request status: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Update final status for both fill request and session execution
     */
    @Transactional
    protected void updateFinalStatus(FillRequest fillRequest, SesstionExecution sessionExecute,
            int successCount, int failCount) {
        try {
            // Update session execution
            sessionExecute.setEndTime(LocalDateTime.now());
            sessionExecute.setSuccessfulExecutions(successCount);
            sessionExecute.setFailedExecutions(failCount);
            sessionExecute.setStatus(FormStatusEnum.COMPLETED);
            sessionExecutionRepository.save(sessionExecute);

            // Determine final status based on success/fail counts
            String finalStatus = (failCount == 0) ? Constants.FILL_REQUEST_STATUS_COMPLETED
                    : Constants.FILL_REQUEST_STATUS_FAILED;

            // Update fill request status
            updateFillRequestStatus(fillRequest, finalStatus);

            log.info("Fill request {} completed. Success: {}, Failed: {}, Final status: {}",
                    fillRequest.getId(), successCount, failCount, finalStatus);
        } catch (Exception e) {
            log.error("Failed to update final status: {}", e.getMessage(), e);
            // Ensure we set failed status even if update fails
            updateFillRequestStatus(fillRequest, Constants.FILL_REQUEST_STATUS_FAILED);
            throw e;
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
                if (question.getRequired()) {
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
                } else {
                    // Handle optional questions as before
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
            log.info("Starting to fill form with {} questions", selections.size());
            long startTime = System.currentTimeMillis();

            // Open browser with optimized settings
            log.info("Opening browser...");
            driver = openBrowser(formUrl);
            long browserOpenTime = System.currentTimeMillis();
            log.info("Browser opened in {}ms", browserOpenTime - startTime);

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30)); // Reduced
                                                                                    // timeout

            // OPTIMIZED: Get all question elements once and cache them
            log.info("Finding question elements...");
            List<WebElement> questionElements;
            try {
                questionElements = driver.findElements(By.xpath("//div[@role='listitem']"));
                long elementsFoundTime = System.currentTimeMillis();
                log.info("Found {} question elements in {}ms", questionElements.size(),
                        elementsFoundTime - browserOpenTime);
            } catch (Exception e) {
                log.error("Failed to find question elements: {}", e.getMessage());
                return false;
            }

            // Build question map once for all questions
            Map<String, WebElement> questionMap = buildQuestionMap(questionElements);
            log.info("Built question map with {} valid questions", questionMap.size());

            // Process each question with optimized logic
            for (Map.Entry<Question, QuestionOption> entry : selections.entrySet()) {
                Question question = entry.getKey();
                QuestionOption option = entry.getValue();

                if (option == null) {
                    log.warn("No option selected for question: {}", question.getTitle());
                    continue;
                }

                try {
                    long questionStartTime = System.currentTimeMillis();
                    log.info("Processing question: {} (type: {})", question.getTitle(),
                            question.getType());

                    // OPTIMIZED: Find question element using pre-built map
                    WebElement questionElement =
                            findQuestionElementFromMap(question.getTitle(), questionMap);
                    long questionFoundTime = System.currentTimeMillis();

                    if (questionElement == null) {
                        log.error("Question not found: {}", question.getTitle());
                        continue;
                    }

                    log.info("Found question '{}' in {}ms", question.getTitle(),
                            questionFoundTime - questionStartTime);

                    // Fill question based on type
                    long fillStartTime = System.currentTimeMillis();
                    switch (question.getType().toLowerCase()) {
                        case "radio":
                            fillRadioQuestion(driver, questionElement, option.getText());
                            break;
                        case "checkbox":
                            fillCheckboxQuestion(driver, questionElement, option.getText());
                            break;
                        case "text":
                        case "short_answer":
                        case "paragraph":
                            fillTextQuestion(driver, questionElement, question.getTitle(),
                                    selections);
                            break;
                        case "combobox":
                        case "select":
                            fillComboboxQuestion(driver, questionElement, question.getTitle(),
                                    option.getText());
                            break;
                        case "multiple_choice_grid":
                            fillMultipleChoiceGridQuestion(driver, questionElement,
                                    option.getText());
                            break;
                        case "checkbox_grid":
                            fillCheckboxGridQuestion(driver, questionElement, option.getText());
                            break;
                        case "date":
                            fillDateQuestion(driver, questionElement, option.getText());
                            break;
                        case "time":
                            fillTimeQuestion(driver, questionElement, option.getText());
                            break;
                        default:
                            log.warn("Unsupported question type: {} for question ID: {}",
                                    question.getType(), question.getId());
                    }
                    long fillEndTime = System.currentTimeMillis();
                    log.info("Filled question '{}' in {}ms", question.getTitle(),
                            fillEndTime - fillStartTime);

                    // OPTIMIZED: Minimal delay between questions
                    if (humanLike) {
                        Thread.sleep(50 + new Random().nextInt(51)); // Reduced from 100-200ms to
                                                                     // 50-100ms
                    }

                } catch (Exception e) {
                    log.error("Error filling question {}: {}", question.getTitle(), e.getMessage());
                }
            }

            // Submit form if enabled
            if (autoSubmitEnabled) {
                try {
                    log.info("Attempting to submit form");
                    WebElement submitButton = wait.until(ExpectedConditions.elementToBeClickable(
                            By.xpath("//div[@role='button' and @aria-label='Submit']")));

                    if (humanLike) {
                        Thread.sleep(500 + new Random().nextInt(501)); // Reduced from 1-2s to
                                                                       // 500-1000ms
                    }

                    submitButton.click();
                    log.info("Form submitted successfully");

                    // Wait for submission confirmation
                    wait.until(ExpectedConditions.urlContains("formResponse"));
                    return true;
                } catch (Exception e) {
                    log.error("Error submitting form: {}", e.getMessage());
                    return false;
                }
            }

            long totalTime = System.currentTimeMillis() - startTime;
            log.info("Form filling completed in {}ms", totalTime);
            return true;

        } catch (Exception e) {
            log.error("Error filling form: {}", e.getMessage(), e);
            return false;
        } finally {
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
     * Find a question element by its title
     *
     * @param questionTitle Title of the question to find
     * @param questionElements List of question elements to search in
     * @return WebElement for the question container
     */
    private WebElement findQuestionElement(String questionTitle,
            List<WebElement> questionElements) {
        try {
            log.debug("Looking for question: '{}'", questionTitle);

            // Build a map of question titles to elements for faster lookup
            Map<String, WebElement> questionMap = new HashMap<>();

            for (WebElement element : questionElements) {
                try {
                    // Check if this element contains a question (has heading)
                    List<WebElement> headings =
                            element.findElements(By.cssSelector("[role='heading']"));
                    if (headings.isEmpty()) {
                        continue; // Skip elements without headings (not questions)
                    }

                    // Get the question title
                    String title = headings.get(0).getText().replace("*", "").trim();
                    if (title.isEmpty()) {
                        continue; // Skip elements with empty titles
                    }

                    // Normalize title for consistent lookup
                    String normalizedTitle = title.toLowerCase();
                    questionMap.put(normalizedTitle, element);

                    log.debug("Mapped question: '{}' -> element", title);

                } catch (Exception e) {
                    log.debug("Error examining question element: {}", e.getMessage());
                    continue;
                }
            }

            log.debug("Built question map with {} valid questions", questionMap.size());

            // Find the question by normalized title
            String normalizedSearchTitle = questionTitle.replace("*", "").trim().toLowerCase();
            WebElement foundElement = questionMap.get(normalizedSearchTitle);

            if (foundElement != null) {
                log.info("Found matching question: '{}'", questionTitle);
                return foundElement;
            }

            log.warn("Question not found: '{}'", questionTitle);
            return null;
        } catch (Exception e) {
            log.error("Error finding question element: {}", e.getMessage(), e);
            return null;
        }
    }

    private WebDriver openBrowser(String formUrl) throws InterruptedException {
        // Setup Chrome options
        ChromeOptions options = new ChromeOptions();

        // Essential Chrome options for stability and visibility
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1920,1080");

        // Set headless mode based on configuration
        if (headless) {
            options.addArguments("--headless=new");
            log.info("Running Chrome in headless mode");
        }

        // Disable automation flags to prevent detection
        options.setExperimentalOption("excludeSwitches",
                Collections.singletonList("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        // Setup Chrome driver
        WebDriverManager.chromedriver().setup();
        WebDriver driver = new ChromeDriver(options);
        String sessionId = ((ChromeDriver) driver).getSessionId().toString();
        log.info("ChromeDriver created successfully with session ID: {}", sessionId);

        // Set timeouts
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(timeoutSeconds));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(timeoutSeconds));

        // Create wait object for waiting for elements
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));

        // Navigate to form URL
        log.info("Navigating to form URL: {}", formUrl);
        driver.get(formUrl);

        // Wait for form to load
        wait.until(
                ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@role='listitem']")));

        // Wait for form to load with alternative selectors
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
        } catch (Exception e) {
            log.warn("Form elements not found with standard selectors, trying alternative...");
        }

        Thread.sleep(2000);
        return driver;
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
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));

            // First verify this is the correct question by checking its title
            String actualQuestionTitle = questionElement
                    .findElement(By.cssSelector("[role='heading']")).getText().trim();
            String expectedTitle = questionElement.getAttribute("aria-label");
            if (expectedTitle == null) {
                // If aria-label is not available, try to get from heading text
                expectedTitle = actualQuestionTitle;
            }

            // Find all radio options within the question container
            List<WebElement> radioOptions =
                    questionElement.findElements(By.cssSelector("[role='radio']"));

            if (radioOptions.isEmpty()) {
                log.warn("No radio options found for question: {}", expectedTitle);
                return;
            }

            // First try exact match with data-value
            for (WebElement radio : radioOptions) {
                String dataValue = radio.getAttribute("data-value");
                if (dataValue != null && dataValue.trim().equals(optionText.trim())) {
                    wait.until(ExpectedConditions.elementToBeClickable(radio));
                    radio.click();
                    log.info("Selected radio option (exact match) for question '{}': {}",
                            expectedTitle, optionText);
                    return;
                }
            }

            // Then try with aria-label
            for (WebElement radio : radioOptions) {
                String ariaLabel = radio.getAttribute("aria-label");
                if (ariaLabel != null && ariaLabel.trim().equals(optionText.trim())) {
                    wait.until(ExpectedConditions.elementToBeClickable(radio));
                    radio.click();
                    log.info("Selected radio option (aria-label match) for question '{}': {}",
                            expectedTitle, optionText);
                    return;
                }
            }

            // Finally try with text content in span
            for (WebElement radio : radioOptions) {
                try {
                    WebElement span = radio.findElement(By
                            .xpath(".//following-sibling::div//span[contains(@class, 'aDTYNe')]"));
                    String spanText = span.getText();
                    if (spanText != null && spanText.trim().equals(optionText.trim())) {
                        wait.until(ExpectedConditions.elementToBeClickable(radio));
                        radio.click();
                        log.info("Selected radio option (text content match) for question '{}': {}",
                                expectedTitle, optionText);
                        return;
                    }
                } catch (Exception ignored) {
                    // Span might not be found, continue to next radio
                }
            }

            // If still not found, try partial match
            for (WebElement radio : radioOptions) {
                String ariaLabel = radio.getAttribute("aria-label");
                if (ariaLabel != null && (ariaLabel.trim().contains(optionText.trim())
                        || optionText.trim().contains(ariaLabel.trim()))) {
                    wait.until(ExpectedConditions.elementToBeClickable(radio));
                    radio.click();
                    log.info("Selected radio option (partial match) for question '{}': {}",
                            expectedTitle, optionText);
                    return;
                }
            }

            log.warn("Radio option not found for question '{}': {}", expectedTitle, optionText);

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
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));

            // First verify this is the correct question by checking its title
            String actualQuestionTitle = questionElement
                    .findElement(By.cssSelector("[role='heading']")).getText().trim();
            // Get title from the heading text directly, don't rely on aria-label
            String expectedTitle = actualQuestionTitle;

            // Find all checkbox options within the question container
            List<WebElement> checkboxOptions =
                    questionElement.findElements(By.cssSelector("[role='checkbox']"));

            if (checkboxOptions.isEmpty()) {
                log.warn("No checkbox options found for question '{}' with text: {}", expectedTitle,
                        optionText);
                return;
            }

            // First try exact match with data-answer-value
            for (WebElement checkbox : checkboxOptions) {
                String dataValue = checkbox.getAttribute("data-answer-value");
                if (dataValue != null && dataValue.trim().equals(optionText.trim())) {
                    wait.until(ExpectedConditions.elementToBeClickable(checkbox));
                    checkbox.click();
                    log.info("Checked checkbox option (exact match) for question '{}': {}",
                            expectedTitle, optionText);

                    // Verify checkbox was checked
                    boolean isChecked = wait.until(
                            ExpectedConditions.attributeToBe(checkbox, "aria-checked", "true"));
                    if (!isChecked) {
                        log.warn("Checkbox may not have been checked properly");
                    }
                    return;
                }
            }

            // Then try with aria-label
            for (WebElement checkbox : checkboxOptions) {
                String ariaLabel = checkbox.getAttribute("aria-label");
                if (ariaLabel != null && ariaLabel.trim().equals(optionText.trim())) {
                    wait.until(ExpectedConditions.elementToBeClickable(checkbox));
                    checkbox.click();
                    log.info("Checked checkbox option (aria-label match) for question '{}': {}",
                            expectedTitle, optionText);

                    // Verify checkbox was checked
                    boolean isChecked = wait.until(
                            ExpectedConditions.attributeToBe(checkbox, "aria-checked", "true"));
                    if (!isChecked) {
                        log.warn("Checkbox may not have been checked properly");
                    }
                    return;
                }
            }

            // Finally try with text content
            for (WebElement checkbox : checkboxOptions) {
                String checkboxText = checkbox.getText().trim();
                // If getText() returns empty, try to find text in child span
                if (checkboxText.isEmpty()) {
                    try {
                        WebElement span = checkbox.findElement(
                                By.xpath(".//following-sibling::div//span[@dir='auto']"));
                        checkboxText = span.getText().trim();
                    } catch (Exception ignored) {
                    }
                }

                if (checkboxText.equals(optionText.trim())) {
                    wait.until(ExpectedConditions.elementToBeClickable(checkbox));
                    checkbox.click();
                    log.info("Checked checkbox option (text content match) for question '{}': {}",
                            expectedTitle, optionText);

                    // Verify checkbox was checked
                    boolean isChecked = wait.until(
                            ExpectedConditions.attributeToBe(checkbox, "aria-checked", "true"));
                    if (!isChecked) {
                        log.warn("Checkbox may not have been checked properly");
                    }
                    return;
                }
            }

            log.warn("Checkbox option not found for question '{}': {}", expectedTitle, optionText);

        } catch (Exception e) {
            log.error("Error filling checkbox question '{}': {}", optionText, e.getMessage());
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
            String questionTitle, Map<Question, QuestionOption> selections)
            throws InterruptedException {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));
            log.info("Processing text question: {}", questionTitle);

            // Verify this is the correct question by checking its title and type
            String actualQuestionTitle = questionElement
                    .findElement(By.cssSelector("[role='heading']")).getText().trim();
            // String actualType = determineQuestionType(questionElement);

            if (!actualQuestionTitle.equals(questionTitle.trim())) {
                // || !actualType.equalsIgnoreCase("text")
                // && !actualType.equalsIgnoreCase("short_answer")
                // && !actualType.equalsIgnoreCase("paragraph")
                // ) {
                log.warn("Question mismatch. Expected: '{}' (text type), Found: '{}'",
                        questionTitle, actualQuestionTitle);
                return;
            }

            // Try multiple possible selectors to find text inputs with explicit wait
            List<WebElement> textInputs;
            try {
                textInputs = questionElement.findElements(By.xpath(
                        ".//input[@type='text'] | .//textarea | .//input[contains(@class, 'quantumWizTextinputPaperinputInput')]"));
            } catch (Exception e) {
                log.debug("Failed to find text inputs with primary selectors, trying alternatives");
                try {
                    textInputs = questionElement.findElements(By.tagName("input"));
                } catch (Exception e2) {
                    textInputs = questionElement.findElements(By.tagName("textarea"));
                }
            }

            if (textInputs.isEmpty()) {
                log.error("No text input found for question: {}", questionTitle);
                return;
            }

            WebElement textInput = textInputs.get(0);
            String textToEnter = null;

            // Find matching question in selections
            for (Map.Entry<Question, QuestionOption> entry : selections.entrySet()) {
                if (entry.getKey().getTitle().equals(questionTitle)) {
                    QuestionOption option = entry.getValue();
                    if (option != null && option.getText() != null && !option.getText().isEmpty()) {
                        textToEnter = option.getText();
                        break;
                    }
                }
            }

            // If no text from user selections, generate random text
            if (textToEnter == null) {
                textToEnter = generateTextByQuestionType(questionTitle);
            }

            // Ensure element is interactive before proceeding
            wait.until(ExpectedConditions.elementToBeClickable(textInput));

            // Click on the text field first to ensure focus
            textInput.click();
            // Clear existing text if any
            textInput.clear();
            // Enter the text with small delays between characters for human-like behavior
            for (char c : textToEnter.toCharArray()) {
                textInput.sendKeys(String.valueOf(c));
                Thread.sleep(50 + new Random().nextInt(50)); // 50-100ms delay between characters
            }

            log.info("Successfully filled text question '{}' with value: {}", questionTitle,
                    textToEnter);
        } catch (Exception e) {
            log.error("Error filling text question '{}': {}", questionTitle, e.getMessage());
            throw e;
        }
    }

    /**
     * Fill a combobox/dropdown question
     *
     * @param driver WebDriver instance
     * @param questionElement Question container element
     * @param questionTitle Title of the question
     * @param optionText Text of the option to select
     */
    private void fillComboboxQuestion(WebDriver driver, WebElement questionElement,
            String questionTitle, String optionText) throws InterruptedException {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));

            // Verify this is the correct question by checking its title and type
            String actualQuestionTitle = questionElement
                    .findElement(By.cssSelector("[role='heading']")).getText().trim();
            String actualType = determineQuestionType(questionElement);

            if (!actualQuestionTitle.equals(questionTitle.trim())
                    || !actualType.equalsIgnoreCase("combobox")) {
                log.warn("Question mismatch. Expected: '{}' (combobox type), Found: '{}' ({} type)",
                        questionTitle, actualQuestionTitle, actualType);
                return;
            }

            // Find and click the dropdown trigger - try multiple selectors
            WebElement dropdownTrigger = null;
            try {
                // First try: find listbox with aria-expanded=false
                dropdownTrigger = questionElement
                        .findElement(By.cssSelector("[role='listbox'][aria-expanded='false']"));
            } catch (Exception e) {
                try {
                    // Second try: find by jsname attribute (Google Forms specific)
                    dropdownTrigger =
                            questionElement.findElement(By.cssSelector("[jsname='W85ice']"));
                } catch (Exception e2) {
                    try {
                        // Third try: find any listbox
                        dropdownTrigger =
                                questionElement.findElement(By.cssSelector("[role='listbox']"));
                    } catch (Exception e3) {
                        log.error("No dropdown trigger found for question: {}", questionTitle);
                        return;
                    }
                }
            }

            // Click to expand the dropdown
            wait.until(ExpectedConditions.elementToBeClickable(dropdownTrigger));
            dropdownTrigger.click();
            log.info("Clicked dropdown trigger for question: {}", questionTitle);

            // Wait for dropdown to expand
            Thread.sleep(1000); // Increased wait time for dropdown to fully expand

            // Find and click the option - try multiple approaches
            WebElement optionElement = null;
            try {
                // First try: find by data-value attribute (most reliable)
                optionElement = questionElement.findElement(
                        By.cssSelector("[role='option'][data-value='" + optionText + "']"));
            } catch (Exception e) {
                try {
                    // Second try: find by text content in span
                    optionElement = questionElement.findElement(
                            By.xpath(".//div[@role='option']//span[text()='" + optionText + "']"));
                } catch (Exception e2) {
                    try {
                        // Third try: find by contains text
                        optionElement = questionElement.findElement(
                                By.xpath(".//div[@role='option']//span[contains(text(), '"
                                        + optionText + "')]"));
                    } catch (Exception e3) {
                        log.error("Option '{}' not found in dropdown for question: {}", optionText,
                                questionTitle);
                        return;
                    }
                }
            }

            // Click the option
            wait.until(ExpectedConditions.elementToBeClickable(optionElement));
            optionElement.click();
            log.info("Selected option '{}' for question: {}", optionText, questionTitle);

        } catch (Exception e) {
            log.error("Error filling combobox question '{}': {}", questionTitle, e.getMessage());
            throw e;
        }
    }

    /**
     * Determine the type of question based on the WebElement
     *
     * @param questionElement The WebElement containing the question
     * @return The type of question (text, radio, checkbox, combobox, etc.)
     */
    private String determineQuestionType(WebElement questionElement) {
        try {
            // Check for date input
            if (!questionElement.findElements(By.cssSelector("input[type='date']")).isEmpty()
                    || !questionElement.findElements(By.cssSelector("[data-includesyear='true']"))
                            .isEmpty()) {
                return "date";
            }

            // Check for checkbox grid
            if (!questionElement.findElements(By.cssSelector("[data-field-id]")).isEmpty()
                    && !questionElement.findElements(By.cssSelector("[role='checkbox']"))
                            .isEmpty()) {
                return "checkbox_grid";
            }

            // Check for multiple choice grid
            if (!questionElement.findElements(By.cssSelector("[data-field-id]")).isEmpty()
                    && !questionElement.findElements(By.cssSelector("[role='radio']")).isEmpty()) {
                return "multiple_choice_grid";
            }

            // Check for time input
            if (!questionElement
                    .findElements(By.cssSelector("input[type='number'][aria-label*='Giờ']"))
                    .isEmpty()
                    || !questionElement
                            .findElements(
                                    By.cssSelector("input[type='number'][aria-label*='Phút']"))
                            .isEmpty()) {
                return "time";
            }

            // Check for radio buttons
            if (!questionElement.findElements(By.cssSelector("[role='radio']")).isEmpty()) {
                return "radio";
            }

            // Check for checkboxes
            if (!questionElement.findElements(By.cssSelector("[role='checkbox']")).isEmpty()) {
                return "checkbox";
            }

            // Check for combobox/dropdown
            if (!questionElement.findElements(By.cssSelector("[role='listbox']")).isEmpty()
                    || !questionElement.findElements(By.cssSelector("[role='combobox']"))
                            .isEmpty()) {
                return "combobox";
            }

            // Check for paragraph (multi-line text)
            if (!questionElement.findElements(By.tagName("textarea")).isEmpty()) {
                return "paragraph";
            }

            // Check for short answer (single line text)
            if (!questionElement.findElements(By.cssSelector("input[type='text']")).isEmpty()) {
                return "short_answer";
            }

            // Default fallback
            return "unknown";
        } catch (Exception e) {
            log.debug("Error determining question type: {}", e.getMessage());
            return "unknown";
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

        // Xử lý các trường hợp email
        if (questionLower.contains("email") || questionLower.contains("thư điện tử")
                || questionLower.contains("mail") || questionLower.matches(".*e[-\\s]?mail.*")) {
            return TestDataEnum.getRandomEmail();
        }

        // Xử lý các trường hợp tên/họ tên
        if (questionLower.matches(".*(?:họ|tên|name|full name|họ và tên|họ tên).*")
                && !questionLower.contains("công ty") && !questionLower.contains("trường")
                && !questionLower.contains("school") && !questionLower.contains("company")) {
            return TestDataEnum.getRandomName();
        }

        // Xử lý các trường hợp số điện thoại
        if (questionLower.matches(".*(?:số điện thoại|phone|sđt|số dt|di động|điện thoại).*")) {
            return TestDataEnum.getRandomPhoneNumber();
        }

        // Xử lý các trường hợp địa chỉ
        if (questionLower.matches(".*(?:địa chỉ|address|nơi ở|location|place).*")
                || (questionLower.contains("số") && questionLower.contains("đường"))) {
            return TestDataEnum.getRandomAddress();
        }

        // Xử lý các trường hợp đánh giá/feedback
        if (questionLower
                .matches(".*(?:đánh giá|feedback|góp ý|nhận xét|comment|ý kiến|cải thiện).*")) {
            return TestDataEnum.getRandomFeedback();
        }

        // Nếu không match với các pattern trên, phân tích thêm context
        if (questionLower.contains("bạn") || questionLower.contains("anh")
                || questionLower.contains("chị") || questionLower.contains("you")) {
            // Câu hỏi mang tính cá nhân
            if (questionLower.matches(".*(?:là ai|who|tên gì).*")) {
                return TestDataEnum.getRandomName();
            }
            if (questionLower.matches(".*(?:ở đâu|where|sống tại).*")) {
                return TestDataEnum.getRandomAddress();
            }
        }

        // Default case: Nếu không match với bất kỳ pattern nào,
        // trả về feedback ngắn gọn để tránh điền sai context
        return "ad";
    }

    /**
     * Builds a map of question titles to their corresponding WebElement containers. This is a
     * helper method to optimize the findQuestionElementFromMap method.
     *
     * @param questionElements The list of WebElement containers for all questions.
     * @return A map of question titles to their corresponding WebElement containers.
     */
    private Map<String, WebElement> buildQuestionMap(List<WebElement> questionElements) {
        Map<String, WebElement> questionMap = new HashMap<>();
        for (WebElement element : questionElements) {
            try {
                // Check if this element contains a question (has heading)
                List<WebElement> headings =
                        element.findElements(By.cssSelector("[role='heading']"));
                if (headings.isEmpty()) {
                    continue; // Skip elements without headings (not questions)
                }

                // Get the question title
                String title = headings.get(0).getText().replace("*", "").trim();
                if (title.isEmpty()) {
                    continue; // Skip elements with empty titles
                }

                // Normalize title for consistent lookup
                String normalizedTitle = title.toLowerCase();
                questionMap.put(normalizedTitle, element);

                log.debug("Mapped question: '{}' -> element", title);

            } catch (Exception e) {
                log.debug("Error examining question element for map building: {}", e.getMessage());
                continue;
            }
        }
        return questionMap;
    }

    /**
     * Finds a WebElement by its title using a pre-built question map.
     *
     * @param questionTitle The title of the question to find.
     * @param questionMap The pre-built map of question titles to WebElements.
     * @return The WebElement for the question container, or null if not found.
     */
    private WebElement findQuestionElementFromMap(String questionTitle,
            Map<String, WebElement> questionMap) {
        String normalizedSearchTitle = questionTitle.replace("*", "").trim().toLowerCase();
        WebElement foundElement = questionMap.get(normalizedSearchTitle);

        if (foundElement != null) {
            log.info("Found matching question: '{}'", questionTitle);
            return foundElement;
        }

        log.warn("Question not found: '{}'", questionTitle);
        return null;
    }

    /**
     * Fill a multiple choice grid question
     */
    private void fillMultipleChoiceGridQuestion(WebDriver driver, WebElement questionElement,
            String optionText) {
        try {
            // Parse the option text format: "row:option"
            String[] parts = optionText.split(":");
            if (parts.length != 2) {
                log.error("Invalid option format for grid question. Expected 'row:option', got: {}",
                        optionText);
                return;
            }

            String rowTitle = parts[0].trim();
            String optionValue = parts[1].trim();

            // Find the row element
            WebElement rowElement = questionElement
                    .findElement(By.xpath(".//div[contains(@class, 'wzWPxe') and contains(text(), '"
                            + rowTitle + "')]//ancestor::div[@role='radiogroup']"));

            if (rowElement == null) {
                log.error("Row not found: {}", rowTitle);
                return;
            }

            // Find and click the radio button for this option in the row
            WebElement radioButton = rowElement.findElement(
                    By.xpath(".//div[@role='radio' and @data-value='" + optionValue + "']"));

            if (radioButton != null) {
                radioButton.click();
                log.info("Selected option '{}' for row '{}' in grid question", optionValue,
                        rowTitle);
            } else {
                log.error("Option '{}' not found in row '{}'", optionValue, rowTitle);
            }

        } catch (Exception e) {
            log.error("Error filling multiple choice grid question: {}", e.getMessage());
        }
    }

    /**
     * Fill a checkbox grid question
     */
    private void fillCheckboxGridQuestion(WebDriver driver, WebElement questionElement,
            String optionText) {
        try {
            // Parse the option text format: "row:option1,option2,..."
            String[] parts = optionText.split(":");
            if (parts.length != 2) {
                log.error(
                        "Invalid option format for checkbox grid. Expected 'row:option1,option2,...', got: {}",
                        optionText);
                return;
            }

            String rowTitle = parts[0].trim();
            String[] optionValues = parts[1].split(",");

            // Find the row element
            WebElement rowElement = questionElement
                    .findElement(By.xpath(".//div[contains(@class, 'wzWPxe') and contains(text(), '"
                            + rowTitle + "')]//ancestor::div[@role='group']"));

            if (rowElement == null) {
                log.error("Row not found: {}", rowTitle);
                return;
            }

            // Click each checkbox option
            for (String optionValue : optionValues) {
                optionValue = optionValue.trim();
                WebElement checkbox = rowElement.findElement(By.xpath(
                        ".//div[@role='checkbox' and @data-answer-value='" + optionValue + "']"));

                if (checkbox != null) {
                    checkbox.click();
                    log.info("Selected option '{}' for row '{}' in checkbox grid", optionValue,
                            rowTitle);
                } else {
                    log.error("Option '{}' not found in row '{}'", optionValue, rowTitle);
                }
            }

        } catch (Exception e) {
            log.error("Error filling checkbox grid question: {}", e.getMessage());
        }
    }

    /**
     * Fill a date question
     */
    private void fillDateQuestion(WebDriver driver, WebElement questionElement, String dateValue) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));

            // Find the date input element
            WebElement dateInput = wait.until(ExpectedConditions
                    .presenceOfElementLocated(By.cssSelector("input[type='date']")));

            if (dateInput == null) {
                log.error("Date input element not found");
                return;
            }

            // Clear existing value if any
            dateInput.clear();

            // Format date value to YYYY-MM-DD if needed
            String formattedDate = dateValue;
            if (!dateValue.matches("\\d{4}-\\d{2}-\\d{2}")) {
                try {
                    SimpleDateFormat inputFormat = new SimpleDateFormat("dd/MM/yyyy");
                    SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM-dd");
                    Date date = inputFormat.parse(dateValue);
                    formattedDate = outputFormat.format(date);
                } catch (ParseException e) {
                    log.error("Error parsing date value: {}", dateValue, e);
                    return;
                }
            }

            // Input the date value
            dateInput.sendKeys(formattedDate);

            // Trigger change event
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].dispatchEvent(new Event('change', { bubbles: true }));",
                    dateInput);

            log.info("Filled date question with value: {}", formattedDate);

        } catch (Exception e) {
            log.error("Error filling date question: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Fill a time question
     */
    private void fillTimeQuestion(WebDriver driver, WebElement questionElement, String timeValue) {
        try {
            // Expected format: HH:mm
            String[] parts = timeValue.split(":");
            if (parts.length != 2) {
                log.error("Invalid time format. Expected 'HH:mm', got: {}", timeValue);
                return;
            }

            String hours = parts[0].trim();
            String minutes = parts[1].trim();

            // Find hour input
            WebElement hourInput = questionElement
                    .findElement(By.cssSelector("input[type='number'][aria-label='Giờ']"));
            if (hourInput != null) {
                hourInput.sendKeys(hours);
            }

            // Find minute input
            WebElement minuteInput = questionElement
                    .findElement(By.cssSelector("input[type='number'][aria-label='Phút']"));
            if (minuteInput != null) {
                minuteInput.sendKeys(minutes);
            }

            log.info("Filled time question with value: {}:{}", hours, minutes);
        } catch (Exception e) {
            log.error("Error filling time question: {}", e.getMessage());
        }
    }
}

