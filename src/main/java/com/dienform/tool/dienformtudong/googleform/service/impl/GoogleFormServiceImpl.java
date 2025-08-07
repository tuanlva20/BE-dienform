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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
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
import com.dienform.tool.dienformtudong.googleform.handler.ComboboxHandler;
import com.dienform.tool.dienformtudong.googleform.handler.GridQuestionHandler;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.googleform.util.DataProcessingUtils;
import com.dienform.tool.dienformtudong.googleform.util.GoogleFormParser;
import com.dienform.tool.dienformtudong.googleform.util.GoogleFormParser.ExtractedQuestion;
import com.dienform.tool.dienformtudong.googleform.util.TestDataEnum;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SesstionExecution;
import com.dienform.tool.dienformtudong.surveyexecution.repository.SessionExecutionRepository;
import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of the GoogleFormService interface
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleFormServiceImpl implements GoogleFormService {

    /**
     * Inner class for checkbox grid answer
     */
    private static class CheckboxGridAnswer {
        private final String row;
        private final List<String> options;

        public CheckboxGridAnswer(String row, List<String> options) {
            this.row = row;
            this.options = options != null ? options : List.of();
        }

        public String getRow() {
            return row;
        }

        public List<String> getOptions() {
            return options;
        }

        public boolean hasSpecificRow() {
            return row != null && !row.trim().isEmpty();
        }
    }

    private final GoogleFormParser googleFormParser;
    private final FillRequestRepository fillRequestRepository;
    private final FormRepository formRepository;
    private final AnswerDistributionRepository answerDistributionRepository;

    private final SessionExecutionRepository sessionExecutionRepository;

    private final ComboboxHandler comboboxHandler;

    // In-memory cache of form questions (URL -> Questions)
    private final Map<String, List<ExtractedQuestion>> formQuestionsCache =
            new ConcurrentHashMap<>();

    @Value("${google.form.auto-submit:true}")
    private boolean autoSubmitEnabled;

    @Value("${google.form.thread-pool-size:5}")
    private int threadPoolSize;

    @Value("${google.form.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${google.form.headless:true}")
    private boolean headless;

    @Value("${google.form.async-enabled:true}")
    private boolean asyncEnabled;

    @Value("${google.form.cache-enabled:true}")
    private boolean cacheEnabled;

    // Performance optimization: Cache question elements to avoid repeated DOM queries
    private final Map<String, List<WebElement>> questionElementsCache = new ConcurrentHashMap<>();

    // OPTIMIZED: Cache for question mapping to improve "Found matching question" speed
    private final Map<String, WebElement> questionMappingCache = new ConcurrentHashMap<>();

    // OPTIMIZED: Thread pool for parallel question processing
    private final ExecutorService questionProcessingExecutor = Executors.newFixedThreadPool(5);

    // OPTIMIZED: Cache for form URL to question map mapping
    private final Map<String, Map<String, WebElement>> formQuestionMapCache =
            new ConcurrentHashMap<>();

    /**
     * Cleanup method to clear caches and shutdown executor
     */
    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up GoogleFormService resources...");

        // Clear caches
        questionElementsCache.clear();
        questionMappingCache.clear();
        formQuestionMapCache.clear();

        // Shutdown executor service
        if (questionProcessingExecutor != null && !questionProcessingExecutor.isShutdown()) {
            questionProcessingExecutor.shutdown();
            try {
                if (!questionProcessingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    questionProcessingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                questionProcessingExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("GoogleFormService cleanup completed");
    }

    /**
     * Clear all caches manually
     */
    public void clearCaches() {
        log.info("Clearing all caches...");
        questionElementsCache.clear();
        questionMappingCache.clear();
        formQuestionMapCache.clear();
        log.info("All caches cleared");
    }

    /**
     * Clear caches for a specific fill request
     */
    public void clearCachesForFillRequest(UUID fillRequestId) {
        String cacheKey = fillRequestId.toString();
        log.info("Clearing caches for fillRequest: {}", fillRequestId);

        questionElementsCache.remove(cacheKey);
        questionMappingCache.remove(cacheKey);
        formQuestionMapCache.remove(cacheKey);

        log.info("Caches cleared for fillRequest: {}", fillRequestId);
    }

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

        // CRITICAL FIX: Add retry mechanism for finding fill request
        final FillRequest fillRequest = findFillRequestWithRetry(fillRequestId);

        // Validate fill request status before starting
        if (!Constants.FILL_REQUEST_STATUS_PENDING.equals(fillRequest.getStatus())
                && !Constants.FILL_REQUEST_STATUS_RUNNING.equals(fillRequest.getStatus())) {
            log.warn("Fill request {} is not in valid state to start. Current status: {}",
                    fillRequestId, fillRequest.getStatus());
            return 0;
        }

        // Clear caches for this specific fill request before starting new execution to avoid stale
        // data
        clearCachesForFillRequest(fillRequestId);

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

        // Create a new executor for this specific fill request to avoid conflicts
        ExecutorService executor = null;
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        Random random = new Random();

        try {
            executor = Executors.newFixedThreadPool(threadPoolSize);

            // Execute form filling tasks
            for (Map<Question, QuestionOption> plan : executionPlans) {
                try {
                    if (fillRequest.isHumanLike()) {
                        // Human-like behavior: random delay between 0.5-2s
                        int delayMillis = 500 + random.nextInt(1501);
                        Thread.sleep(delayMillis);
                        log.debug("Human-like mode: Scheduled task with delay of {}ms",
                                delayMillis);
                    } else {
                        // Fast mode: only 1 second delay between forms, no delay during filling
                        if (totalProcessed.get() > 0) {
                            Thread.sleep(1000); // 1 second between forms
                            log.debug("Fast mode: 1 second delay between forms");
                        }
                    }

                    futures.add(CompletableFuture.runAsync(() -> {
                        executeFormFillTask(fillRequestId, link, plan, fillRequest.isHumanLike(),
                                successCount, failCount);
                    }, executor));

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
                log.info("All form filling tasks completed successfully");
            } catch (Exception e) {
                log.error("Form filling execution was interrupted or timed out", e);
                updateFillRequestStatus(fillRequest, Constants.FILL_REQUEST_STATUS_FAILED);
                return 0;
            }

        } finally {
            // Ensure executor is properly shutdown
            if (executor != null) {
                try {
                    executor.shutdown();
                    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                            log.error("Executor did not terminate");
                        }
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            // Clear caches for this specific fill request after execution to free memory
            clearCachesForFillRequest(fillRequestId);
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
    public boolean submitFormWithBrowser(UUID formId, String formUrl,
            Map<String, String> formData) {
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

            // Use existing executeFormFill method with formId as cache key
            return executeFormFill(formId, formUrl, selections, true);
        } catch (Exception e) {
            log.error("Error submitting form: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Debug method to test text input detection This method can be used to verify that text inputs
     * are being found correctly
     * 
     * @param questionElement The question container element
     * @return Debug information about the text input detection
     */
    public String debugTextInputDetection(WebElement questionElement) {
        try {
            StringBuilder debug = new StringBuilder();
            debug.append("=== Text Input Detection Debug ===\n");

            // Test primary selector (type attribute)
            List<WebElement> primaryInputs = questionElement
                    .findElements(By.cssSelector("input[type='text'], input[type='email']"));
            debug.append("Primary selector (type attribute): ").append(primaryInputs.size())
                    .append(" elements\n");

            if (!primaryInputs.isEmpty()) {
                WebElement input = primaryInputs.get(0);
                debug.append("  - Type: ").append(input.getAttribute("type")).append("\n");
                debug.append("  - Class: ").append(input.getAttribute("class")).append("\n");
                debug.append("  - Aria-label: ").append(input.getAttribute("aria-label"))
                        .append("\n");
                debug.append("  - Tag name: ").append(input.getTagName()).append("\n");
            }

            // Test secondary selector (textarea)
            List<WebElement> secondaryInputs = questionElement.findElements(By.tagName("textarea"));
            debug.append("Secondary selector (textarea): ").append(secondaryInputs.size())
                    .append(" elements\n");

            // Test tertiary selector (any input element)
            List<WebElement> tertiaryInputs = questionElement.findElements(By.tagName("input"));
            debug.append("Tertiary selector (any input element): ").append(tertiaryInputs.size())
                    .append(" elements\n");

            // Test quaternary selector (fallback)
            List<WebElement> quaternaryInputs = questionElement.findElements(By.tagName("input"));
            debug.append("Quaternary selector (fallback): ").append(quaternaryInputs.size())
                    .append(" elements\n");

            // Test the actual findTextInputElement method
            WebElement foundInput = findTextInputElement(questionElement);
            debug.append("findTextInputElement result: ")
                    .append(foundInput != null ? "FOUND" : "NOT FOUND").append("\n");

            if (foundInput != null) {
                debug.append("  - Found input type: ").append(foundInput.getAttribute("type"))
                        .append("\n");
                debug.append("  - Found input tag: ").append(foundInput.getTagName()).append("\n");
            }

            return debug.toString();

        } catch (Exception e) {
            return "Error in debug: " + e.getMessage();
        }
    }

    /**
     * Debug method to test combobox detection and structure
     * 
     * @param questionElement The question container element
     * @param driver WebDriver instance
     * @return Debug information about the combobox structure
     */
    public String debugComboboxDetection(WebElement questionElement, WebDriver driver) {
        try {
            StringBuilder debug = new StringBuilder();
            debug.append("=== Combobox Detection Debug ===\n");

            // Test dropdown trigger
            List<WebElement> dropdownTriggers =
                    questionElement.findElements(By.cssSelector("[role='listbox']"));
            debug.append("Dropdown triggers found: ").append(dropdownTriggers.size()).append("\n");

            if (!dropdownTriggers.isEmpty()) {
                WebElement trigger = dropdownTriggers.get(0);
                debug.append("  - Trigger class: ").append(trigger.getAttribute("class"))
                        .append("\n");
                debug.append("  - Trigger aria-expanded: ")
                        .append(trigger.getAttribute("aria-expanded")).append("\n");
                debug.append("  - Trigger role: ").append(trigger.getAttribute("role"))
                        .append("\n");
            }

            // Test popup containers using stable selectors based on HTML structure
            List<WebElement> popupContainers = driver.findElements(By.xpath(
                    "//div[@role='presentation' and .//div[@role='option'] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));
            debug.append("Popup containers found (primary selector): ")
                    .append(popupContainers.size()).append("\n");

            for (int i = 0; i < popupContainers.size(); i++) {
                WebElement container = popupContainers.get(i);
                String style = container.getAttribute("style");
                String display = container.getCssValue("display");
                String role = container.getAttribute("role");
                debug.append("  Container ").append(i + 1).append(":\n");
                debug.append("    - Role: ").append(role).append("\n");
                debug.append("    - Style: ").append(style).append("\n");
                debug.append("    - Display: ").append(display).append("\n");
                debug.append("    - Visible: ").append(container.isDisplayed()).append("\n");
            }

            // Test alternative popup selectors
            List<WebElement> altContainers = driver.findElements(By.xpath(
                    "//div[.//div[@role='option'] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));
            debug.append("Alternative popup containers found: ").append(altContainers.size())
                    .append("\n");

            // Test options in page
            List<WebElement> allOptions = driver.findElements(By.cssSelector("[role='option']"));
            debug.append("All options in page: ").append(allOptions.size()).append("\n");

            // Test options in specific popup container
            List<WebElement> popupOptions = new ArrayList<>();
            if (!popupContainers.isEmpty()) {
                popupOptions =
                        popupContainers.get(0).findElements(By.cssSelector("[role='option']"));
                debug.append("Options in first popup container: ").append(popupOptions.size())
                        .append("\n");
            }

            for (int i = 0; i < Math.min(allOptions.size(), 5); i++) {
                WebElement option = allOptions.get(i);
                String dataValue = option.getAttribute("data-value");
                String ariaSelected = option.getAttribute("aria-selected");
                String text = option.getText();
                String spanText = "";
                try {
                    // First try: direct span child
                    WebElement span = option.findElement(By.tagName("span"));
                    spanText = span.getText();
                    if (spanText.isEmpty()) {
                        // Second try: any span with text content
                        span = option.findElement(By.xpath(".//span[text()]"));
                        spanText = span.getText();
                    }
                    if (spanText.isEmpty()) {
                        // Third try: any element with text content
                        span = option.findElement(By.xpath(".//*[text()]"));
                        spanText = span.getText();
                    }
                } catch (Exception ignore) {
                }
                debug.append("  Option ").append(i + 1).append(":\n");
                debug.append("    - Data-value: '").append(dataValue).append("'\n");
                debug.append("    - Aria-selected: ").append(ariaSelected).append("\n");
                debug.append("    - Text: '").append(text).append("'\n");
                debug.append("    - Span text: '").append(spanText).append("'\n");
            }

            // Also show options from popup container if available
            if (!popupOptions.isEmpty()) {
                debug.append("Options from popup container:\n");
                for (int i = 0; i < Math.min(popupOptions.size(), 5); i++) {
                    WebElement option = popupOptions.get(i);
                    String dataValue = option.getAttribute("data-value");
                    String ariaSelected = option.getAttribute("aria-selected");
                    String text = option.getText();
                    String spanText = "";
                    try {
                        // First try: direct span child
                        WebElement span = option.findElement(By.tagName("span"));
                        spanText = span.getText();
                        if (spanText.isEmpty()) {
                            // Second try: any span with text content
                            span = option.findElement(By.xpath(".//span[text()]"));
                            spanText = span.getText();
                        }
                        if (spanText.isEmpty()) {
                            // Third try: any element with text content
                            span = option.findElement(By.xpath(".//*[text()]"));
                            spanText = span.getText();
                        }
                    } catch (Exception ignore) {
                    }
                    debug.append("  Popup Option ").append(i + 1).append(":\n");
                    debug.append("    - Data-value: '").append(dataValue).append("'\n");
                    debug.append("    - Aria-selected: ").append(ariaSelected).append("\n");
                    debug.append("    - Text: '").append(text).append("'\n");
                    debug.append("    - Span text: '").append(spanText).append("'\n");
                }
            }

            return debug.toString();

        } catch (Exception e) {
            return "Error in combobox debug: " + e.getMessage();
        }
    }

    /**
     * Reset fill request status to PENDING if it's stuck in RUNNING state
     */
    @Transactional
    public void resetFillRequestStatus(UUID fillRequestId) {
        try {
            FillRequest fillRequest = fillRequestRepository.findById(fillRequestId).orElseThrow(
                    () -> new ResourceNotFoundException("Fill Request", "id", fillRequestId));

            if (Constants.FILL_REQUEST_STATUS_RUNNING.equals(fillRequest.getStatus())) {
                fillRequest.setStatus(Constants.FILL_REQUEST_STATUS_PENDING);
                fillRequestRepository.save(fillRequest);
                log.info("Reset fill request {} status from RUNNING to PENDING", fillRequestId);
            } else {
                log.info("Fill request {} is not in RUNNING state, current status: {}",
                        fillRequestId, fillRequest.getStatus());
            }
        } catch (Exception e) {
            log.error("Failed to reset fill request status: {}", e.getMessage(), e);
            throw e;
        }
    }

    public Map<String, List<WebElement>> getQuestionElementsCache() {
        return questionElementsCache;
    }

    public Map<String, Map<String, WebElement>> getFormQuestionMapCache() {
        return formQuestionMapCache;
    }

    public Map<String, WebElement> getQuestionMappingCache() {
        return questionMappingCache;
    }

    /**
     * Get cached question elements for a specific fill request
     */
    public List<WebElement> getQuestionElementsCacheForFillRequest(UUID fillRequestId) {
        return questionElementsCache.get(fillRequestId.toString());
    }

    /**
     * Get cached question map for a specific fill request
     */
    public Map<String, WebElement> getFormQuestionMapCacheForFillRequest(UUID fillRequestId) {
        return formQuestionMapCache.get(fillRequestId.toString());
    }

    public Map<String, List<ExtractedQuestion>> getFormQuestionsCache() {
        return formQuestionsCache;
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
     * Verify that popup container contains the correct options for the question
     */
    private boolean verifyPopupContainerOptions(WebElement popupContainer, String questionTitle) {
        try {
            // Get all options in the popup container
            List<WebElement> options =
                    popupContainer.findElements(By.cssSelector("[role='option']"));

            if (options.isEmpty()) {
                log.debug("No options found in popup container for question: {}", questionTitle);
                return false;
            }

            // Log the first few options for debugging
            log.debug("Popup container options for question '{}':", questionTitle);
            for (int i = 0; i < Math.min(3, options.size()); i++) {
                WebElement option = options.get(i);
                String dataValue = option.getAttribute("data-value");
                String spanText = "";
                try {
                    WebElement span = option.findElement(By.tagName("span"));
                    spanText = span.getText();
                } catch (Exception ignore) {
                }
                log.debug("Option {}: data-value='{}', spanText='{}'", i + 1, dataValue, spanText);
            }

            // For now, just check if we have valid options (not empty data-value)
            long validOptions = options.stream().map(option -> option.getAttribute("data-value"))
                    .filter(dataValue -> dataValue != null && !dataValue.trim().isEmpty()).count();

            log.debug("Found {} valid options in popup container for question: {}", validOptions,
                    questionTitle);

            return validOptions > 0;

        } catch (Exception e) {
            log.debug("Error verifying popup container options: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verify that popup container belongs to the correct dropdown
     * 
     * @param popupContainer The popup container element
     * @param dropdownTrigger The dropdown trigger element
     * @param questionTitle The question title
     * @return True if popup container belongs to the dropdown, false otherwise
     */
    private boolean verifyPopupContainerBelongsToDropdown(WebElement popupContainer,
            WebElement dropdownTrigger, String questionTitle) {
        try {
            // Method 1: Check if popup container contains the expected options for this question
            // This is a simple verification - if the options don't match the question, it's wrong
            List<WebElement> options =
                    popupContainer.findElements(By.cssSelector("[role='option']"));

            // Get the dropdown's aria-labelledby to identify which question it belongs to
            String dropdownAriaLabelledBy = dropdownTrigger.getAttribute("aria-labelledby");
            log.debug("Verifying popup container for dropdown with aria-labelledby: {}",
                    dropdownAriaLabelledBy);

            // For now, just log the options and let the calling code decide
            log.debug("Popup container has {} options", options.size());
            for (int i = 0; i < Math.min(options.size(), 3); i++) {
                WebElement option = options.get(i);
                String dataValue = option.getAttribute("data-value");
                log.debug("Option {}: data-value='{}'", i + 1, dataValue);
            }

            // If we have options, assume it's correct for now
            // The real verification will be done when trying to find the target option
            return !options.isEmpty();

        } catch (Exception e) {
            log.error("Error verifying popup container belongs to dropdown: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verify that dropdown trigger belongs to the correct question
     * 
     * @param dropdownTrigger The dropdown trigger element
     * @param questionElement The question container element
     * @param questionTitle The question title
     * @return True if dropdown belongs to the question, false otherwise
     */
    private boolean verifyDropdownBelongsToQuestion(WebElement dropdownTrigger,
            WebElement questionElement, String questionTitle) {
        try {
            // Simple verification: check if dropdown is within the question element
            // Since we found the dropdown using questionElement.findElement(), it should be correct
            // But let's add some additional logging for debugging

            String dropdownAriaLabelledBy = dropdownTrigger.getAttribute("aria-labelledby");
            String dropdownAriaDescribedBy = dropdownTrigger.getAttribute("aria-describedby");

            log.debug("Dropdown verification for question '{}':", questionTitle);
            log.debug("  - aria-labelledby: {}", dropdownAriaLabelledBy);
            log.debug("  - aria-describedby: {}", dropdownAriaDescribedBy);
            log.debug("  - aria-expanded: {}", dropdownTrigger.getAttribute("aria-expanded"));

            // Since we found the dropdown within the question element, it should be correct
            // But let's verify by checking if the question element contains this dropdown
            try {
                questionElement.findElement(By.cssSelector("[role='listbox']"));
                log.debug("Dropdown trigger verified to belong to question: {}", questionTitle);
                return true;
            } catch (Exception e) {
                log.warn("Dropdown trigger not found within question element: {}", questionTitle);
                return false;
            }

        } catch (Exception e) {
            log.error("Error verifying dropdown belongs to question: {}", e.getMessage());
            return false;
        }
    }

    /**
     * OPTIMIZED: Get question elements with caching and retry mechanism
     */
    private List<WebElement> getQuestionElementsWithRetry(WebDriver driver, UUID fillRequestId) {
        String cacheKey = fillRequestId.toString();
        if (cacheEnabled) {
            List<WebElement> cachedElements = questionElementsCache.get(cacheKey);
            if (cachedElements != null && !cachedElements.isEmpty()) {
                log.info("Using cached question elements for fillRequest {}: {}", fillRequestId,
                        cachedElements.size());
                return cachedElements;
            }
        }

        // Get elements with retry mechanism
        List<WebElement> questionElements = new ArrayList<>();
        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                questionElements = driver.findElements(By.xpath("//div[@role='listitem']"));
                if (!questionElements.isEmpty()) {
                    // Cache the result
                    if (cacheEnabled) {
                        questionElementsCache.put(cacheKey, questionElements);
                    }
                    log.info("Found {} question elements for fillRequest {} (attempt {})",
                            questionElements.size(), fillRequestId, retryCount + 1);
                    break;
                }
            } catch (Exception e) {
                log.warn("Failed to find question elements for fillRequest {} (attempt {}): {}",
                        fillRequestId, retryCount + 1, e.getMessage());
            }

            retryCount++;
            if (retryCount < maxRetries) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (questionElements.isEmpty()) {
            log.error("Failed to find question elements for fillRequest {} after {} attempts",
                    fillRequestId, maxRetries);
        }

        return questionElements;
    }

    /**
     * OPTIMIZED: Find question element with performance monitoring
     */
    private WebElement findQuestionWithPerformanceMonitoring(String questionTitle,
            Map<String, WebElement> questionMap, UUID fillRequestId) {
        long startTime = System.currentTimeMillis();

        WebElement result = findQuestionElementFromMap(questionTitle, questionMap, fillRequestId);

        long duration = System.currentTimeMillis() - startTime;

        if (result != null) {
            log.info("Found question '{}' for fillRequest {} in {}ms", questionTitle, fillRequestId,
                    duration);
        } else {
            log.warn("Question '{}' for fillRequest {} not found after {}ms", questionTitle,
                    fillRequestId, duration);
        }

        return result;
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
                    // Handle text questions (text, email, textarea)
                    if ("text".equalsIgnoreCase(question.getType())
                            || "email".equalsIgnoreCase(question.getType())
                            || "textarea".equalsIgnoreCase(question.getType())) {
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
                    // Handle text questions (text, email, textarea)
                    if ("text".equalsIgnoreCase(question.getType())
                            || "email".equalsIgnoreCase(question.getType())
                            || "textarea".equalsIgnoreCase(question.getType())) {
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
     * @param fillRequestId The ID of the fill request for caching
     * @param formUrl The URL of the form to fill
     * @param selections Map of questions to selected options
     * @param humanLike Whether to simulate human-like behavior
     * @return True if successful, false otherwise
     */
    private boolean executeFormFill(UUID fillRequestId, String formUrl,
            Map<Question, QuestionOption> selections, boolean humanLike) {
        WebDriver driver = null;
        try {
            log.info("Starting to fill form with {} questions", selections.size());
            long startTime = System.currentTimeMillis();

            // Open browser with optimized settings
            log.info("Opening browser...");
            driver = openBrowser(formUrl, humanLike);
            long browserOpenTime = System.currentTimeMillis();
            log.info("Browser opened in {}ms", browserOpenTime - startTime);

            // OPTIMIZED: Use shorter timeout for element operations
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15)); // Reduced from
                                                                                    // 30s to 15s

            // OPTIMIZED: Get all question elements once and cache them with retry mechanism
            log.info("Finding question elements...");
            List<WebElement> questionElements = getQuestionElementsWithRetry(driver, fillRequestId);

            if (questionElements.isEmpty()) {
                log.error("Failed to find question elements");
                return false;
            }

            // OPTIMIZED: Build question map with performance logging and caching
            long mapStartTime = System.currentTimeMillis();
            Map<String, WebElement> questionMap;

            // OPTIMIZED: Check cache first for question map
            if (cacheEnabled) {
                questionMap = formQuestionMapCache.get(fillRequestId.toString());
                if (questionMap != null) {
                    log.info("Using cached question map with {} questions", questionMap.size());
                } else {
                    questionMap = buildQuestionMap(questionElements);
                    // Cache the question map for future use
                    formQuestionMapCache.put(fillRequestId.toString(), questionMap);
                    log.info("Built and cached question map with {} valid questions in {}ms",
                            questionMap.size(), System.currentTimeMillis() - mapStartTime);
                }
            } else {
                questionMap = buildQuestionMap(questionElements);
                log.info("Built question map with {} valid questions in {}ms", questionMap.size(),
                        System.currentTimeMillis() - mapStartTime);
            }

            // OPTIMIZED: Process each question with better error handling
            int processedQuestions = 0;

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

                    // OPTIMIZED: Find question element using pre-built map with performance
                    // monitoring
                    WebElement questionElement = findQuestionWithPerformanceMonitoring(
                            question.getTitle(), questionMap, fillRequestId);
                    long questionFoundTime = System.currentTimeMillis();

                    if (questionElement == null) {
                        log.error("Question not found: {}", question.getTitle());
                        continue;
                    }

                    log.info("Found question '{}' in {}ms", question.getTitle(),
                            questionFoundTime - questionStartTime);

                    // OPTIMIZED: Fill question based on type with shorter timeouts
                    long fillStartTime = System.currentTimeMillis();
                    boolean fillSuccess = fillQuestionByType(driver, questionElement, question,
                            option, humanLike);
                    long fillEndTime = System.currentTimeMillis();

                    if (fillSuccess) {
                        processedQuestions++;
                        log.info("Filled question '{}' in {}ms", question.getTitle(),
                                fillEndTime - fillStartTime);
                    } else {
                        log.warn("Failed to fill question '{}'", question.getTitle());
                    }

                    // OPTIMIZED: Reduced delay between questions
                    if (humanLike) {
                        Thread.sleep(25 + new Random().nextInt(26)); // 25-50ms delay between
                                                                     // questions
                    }

                } catch (Exception e) {
                    log.error("Error filling question {}: {}", question.getTitle(), e.getMessage());
                }
            }

            log.info("Successfully processed {}/{} questions", processedQuestions,
                    selections.size());

            // OPTIMIZED: Submit form if enabled with shorter timeout
            if (autoSubmitEnabled) {
                try {
                    log.info("Attempting to submit form");
                    WebElement submitButton = wait.until(ExpectedConditions.elementToBeClickable(
                            By.xpath("//div[@role='button' and @aria-label='Submit']")));

                    if (humanLike) {
                        Thread.sleep(250 + new Random().nextInt(251)); // 250-500ms delay before
                                                                       // submit (reduced from
                                                                       // 500-1000ms)
                    }

                    submitButton.click();
                    log.info("Form submitted successfully");

                    // OPTIMIZED: Wait for submission confirmation with shorter timeout
                    WebDriverWait submitWait = new WebDriverWait(driver, Duration.ofSeconds(10));
                    submitWait.until(ExpectedConditions.urlContains("formResponse"));
                    return true;
                } catch (Exception e) {
                    log.error("Error submitting form: {}", e.getMessage());
                    return false;
                }
            }

            long totalTime = System.currentTimeMillis() - startTime;
            log.info("Form filling completed in {}ms", totalTime);
            return processedQuestions > 0; // Return true if at least one question was processed

        } catch (Exception e) {
            log.error("Error filling form: {}", e.getMessage(), e);
            // Don't quit driver here, let finally block handle it
            return false;
        } finally {
            // Ensure WebDriver is properly closed
            if (driver != null) {
                try {
                    // Check if driver is still active before quitting
                    if (!isWebDriverQuit(driver)) {
                        driver.quit();
                        log.info("WebDriver closed successfully");
                    } else {
                        log.info("WebDriver was already quit");
                    }
                } catch (Exception e) {
                    log.error("Error closing WebDriver: {}", e.getMessage());
                    // Force quit if normal quit fails
                    try {
                        if (!isWebDriverQuit(driver)) {
                            driver.close();
                        }
                    } catch (Exception closeException) {
                        log.error("Error force closing WebDriver: {}", closeException.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Fill question based on type with optimized performance
     */
    private boolean fillQuestionByType(WebDriver driver, WebElement questionElement,
            Question question, QuestionOption option, boolean humanLike) {
        try {
            // Check if WebDriver is still active
            if (driver == null || isWebDriverQuit(driver)) {
                log.error("WebDriver is null or has been quit, cannot fill question: {}",
                        question.getTitle());
                return false;
            }

            // Debug: Log the question type and option text
            log.info("Processing question: '{}' (type: {}) with option: '{}'", question.getTitle(),
                    question.getType(), option.getText());

            switch (question.getType().toLowerCase()) {
                case "radio":
                    fillRadioQuestion(driver, questionElement, option.getText(), humanLike);
                    return true;
                case "checkbox":
                    fillCheckboxQuestion(driver, questionElement, option.getText(), humanLike);
                    return true;
                case "text":
                case "email":
                case "textarea":
                case "short_answer":
                case "paragraph":
                    fillTextQuestion(driver, questionElement, question.getTitle(),
                            Map.of(question, option), humanLike);
                    return true;
                case "combobox":
                case "select":
                    fillComboboxQuestion(driver, questionElement, question.getTitle(),
                            option.getText(), humanLike);
                    return true;
                case "multiple_choice_grid":
                    fillMultipleChoiceGridQuestion(driver, questionElement, question, option,
                            humanLike);
                    return true;
                case "checkbox_grid":
                    fillCheckboxGridQuestion(driver, questionElement, question, option, humanLike);
                    return true;
                case "date":
                    fillDateQuestion(driver, questionElement, option.getText(), humanLike);
                    return true;
                case "time":
                    fillTimeQuestion(driver, questionElement, option.getText(), humanLike);
                    return true;
                default:
                    log.warn("Unsupported question type: {} for question ID: {}",
                            question.getType(), question.getId());
                    return false;
            }
        } catch (Exception e) {
            log.error("Error filling question {} of type {}: {}", question.getTitle(),
                    question.getType(), e.getMessage());
            return false;
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

    private WebDriver openBrowser(String formUrl, boolean humanLike) throws InterruptedException {
        // Setup Chrome options with optimized settings
        ChromeOptions options = new ChromeOptions();

        // Essential Chrome options for stability and performance
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1920,1080");

        // Performance optimizations
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-plugins");
        options.addArguments("--disable-images");
        // options.addArguments("--disable-javascript");
        options.addArguments("--disable-web-security");
        options.addArguments("--disable-features=VizDisplayCompositor");

        // Additional options to prevent detection and improve stability
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--disable-infobars");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-save-password-bubble");
        options.addArguments("--disable-translate");
        options.addArguments("--no-first-run");
        options.addArguments("--no-default-browser-check");

        // Set headless mode based on configuration
        if (headless) {
            options.addArguments("--headless=new");
            log.info("Running Chrome in headless mode");
        }

        // Disable automation flags to prevent detection
        options.setExperimentalOption("excludeSwitches",
                Collections.singletonList("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        // Setup Chrome driver with retry mechanism
        WebDriver driver = null;
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                WebDriverManager.chromedriver().setup();
                driver = new ChromeDriver(options);
                String sessionId = ((ChromeDriver) driver).getSessionId().toString();
                log.info("ChromeDriver created successfully with session ID: {}", sessionId);
                break;
            } catch (Exception e) {
                retryCount++;
                log.warn("Failed to create ChromeDriver (attempt {}/{}): {}", retryCount,
                        maxRetries, e.getMessage());

                if (retryCount >= maxRetries) {
                    throw new RuntimeException(
                            "Failed to create ChromeDriver after " + maxRetries + " attempts", e);
                }

                // Wait before retry
                Thread.sleep(1000 * retryCount);
            }
        }

        if (driver == null) {
            throw new RuntimeException("Failed to create ChromeDriver");
        }

        // Set optimized timeouts - reduced from 180s to 30s
        int optimizedTimeout = Math.min(timeoutSeconds, 30); // Cap at 30 seconds
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(optimizedTimeout));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10)); // Reduced implicit wait

        // Create wait object with shorter timeout
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15)); // Reduced from 180s
                                                                                // to 15s

        // Navigate to form URL with retry mechanism
        log.info("Navigating to form URL: {}", formUrl);
        int navigationRetries = 3;
        int navigationRetryCount = 0;

        while (navigationRetryCount < navigationRetries) {
            try {
                driver.get(formUrl);
                break;
            } catch (Exception e) {
                navigationRetryCount++;
                log.warn("Failed to navigate to form URL (attempt {}/{}): {}", navigationRetryCount,
                        navigationRetries, e.getMessage());

                if (navigationRetryCount >= navigationRetries) {
                    throw new RuntimeException("Failed to navigate to form URL after "
                            + navigationRetries + " attempts", e);
                }

                // Wait before retry
                Thread.sleep(2000 * navigationRetryCount);
            }
        }

        // Wait for form to load completely with multiple strategies
        try {
            log.info("Waiting for form to load completely...");

            // Strategy 1: Wait for Google Forms specific elements
            wait.until(ExpectedConditions
                    .presenceOfElementLocated(By.xpath("//div[@role='listitem']")));
            log.info("Form elements found successfully with primary selector");

            // Strategy 2: Wait for form container
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
            log.info("Form container found successfully");

            // Strategy 3: Wait for at least one question to be fully loaded
            wait.until(ExpectedConditions
                    .presenceOfElementLocated(By.cssSelector("[role='heading']")));
            log.info("Question headings found successfully");

            // Additional wait to ensure JavaScript is fully executed
            Thread.sleep(2000);
            log.info("Form loading completed successfully");

        } catch (Exception e) {
            log.warn("Primary selectors failed, trying alternative: {}", e.getMessage());
            // Fallback: try alternative selector with shorter timeout
            try {
                WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(10));
                shortWait
                        .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("form")));
                log.info("Form elements found with alternative selector");
                // Thread.sleep(1000); // Additional wait
            } catch (Exception e2) {
                log.warn("Alternative selector also failed, proceeding anyway: {}",
                        e2.getMessage());
                // Continue anyway - elements might be loaded but not detected by selectors
                // Thread.sleep(2000); // Wait a bit more before proceeding
            }
        }

        // Minimal delay only in human-like mode
        if (humanLike) {
            Thread.sleep(500); // Reduced from 2000ms to 500ms
        }

        return driver;
    }

    /**
     * Fill a radio button question with optimized performance
     *
     * @param driver WebDriver instance
     * @param questionElement Question container element
     * @param optionText Text of the option to select
     */
    private void fillRadioQuestion(WebDriver driver, WebElement questionElement, String optionText,
            boolean humanLike) {
        try {
            // Check if WebDriver is still active
            if (driver == null || isWebDriverQuit(driver)) {
                log.error("WebDriver is null or has been quit, cannot fill radio question");
                return;
            }

            // OPTIMIZED: Use shorter timeout for radio operations
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10)); // Reduced from
                                                                                    // 180s to 10s

            // OPTIMIZED: Skip title verification for performance
            // String actualQuestionTitle = questionElement
            // .findElement(By.cssSelector("[role='heading']")).getText().trim();

            // Find all radio options within the question container
            List<WebElement> radioOptions =
                    questionElement.findElements(By.cssSelector("[role='radio']"));

            if (radioOptions.isEmpty()) {
                log.warn("No radio options found for question");
                return;
            }

            // OPTIMIZED: Try exact match with data-value first (most efficient)
            for (WebElement radio : radioOptions) {
                try {
                    String dataValue = radio.getAttribute("data-value");
                    if (dataValue != null && dataValue.trim().equals(optionText.trim())) {
                        // OPTIMIZED: Use shorter wait for clickability
                        wait.until(ExpectedConditions.elementToBeClickable(radio));
                        radio.click();
                        log.info("Selected radio option (exact match): {}", optionText);
                        return;
                    }
                } catch (Exception e) {
                    // Continue to next option if this one fails
                    continue;
                }
            }

            // OPTIMIZED: Try with aria-label as fallback
            for (WebElement radio : radioOptions) {
                try {
                    String ariaLabel = radio.getAttribute("aria-label");
                    if (ariaLabel != null && ariaLabel.trim().equals(optionText.trim())) {
                        wait.until(ExpectedConditions.elementToBeClickable(radio));
                        radio.click();
                        log.info("Selected radio option (aria-label match): {}", optionText);
                        return;
                    }
                } catch (Exception e) {
                    // Continue to next option if this one fails
                    continue;
                }
            }

            // OPTIMIZED: Try partial match as last resort
            for (WebElement radio : radioOptions) {
                try {
                    String ariaLabel = radio.getAttribute("aria-label");
                    if (ariaLabel != null
                            && ariaLabel.toLowerCase().contains(optionText.toLowerCase())) {
                        wait.until(ExpectedConditions.elementToBeClickable(radio));
                        radio.click();
                        log.info("Selected radio option (partial match): {}", optionText);
                        return;
                    }
                } catch (Exception e) {
                    // Continue to next option if this one fails
                    continue;
                }
            }

            log.warn("No matching radio option found for: {}", optionText);

        } catch (Exception e) {
            log.error("Error filling radio question: {}", e.getMessage());
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
            String optionText, boolean humanLike) {
        try {
            // Check if WebDriver is still active
            if (driver == null || isWebDriverQuit(driver)) {
                log.error("WebDriver is null or has been quit, cannot fill checkbox question");
                return;
            }

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
     * Fill a text input question using stable selectors based on Google Form structure
     *
     * @param driver WebDriver instance
     * @param questionElement Question container element
     * @param questionTitle Title of the question to determine text input type
     * @param selections Map chứa các câu hỏi và option được chọn
     */
    private void fillTextQuestion(WebDriver driver, WebElement questionElement,
            String questionTitle, Map<Question, QuestionOption> selections, boolean humanLike)
            throws InterruptedException {
        try {
            // Check if WebDriver is still active
            if (driver == null || isWebDriverQuit(driver)) {
                log.error("WebDriver is null or has been quit, cannot fill text question: {}",
                        questionTitle);
                return;
            }

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
            log.info("Processing text question: {}", questionTitle);

            // Verify this is the correct question by checking its title with normalized comparison
            String actualQuestionTitle =
                    questionElement.findElement(By.cssSelector("[role='heading']")).getText();

            // Normalize both titles for comparison (remove extra whitespace and newlines)
            String normalizedExpectedTitle = normalizeQuestionTitle(questionTitle);
            String normalizedActualTitle = normalizeQuestionTitle(actualQuestionTitle);

            if (!normalizedActualTitle.equals(normalizedExpectedTitle)) {
                log.warn(
                        "Question mismatch. Expected: '{}', Found: '{}' (Normalized: '{}' vs '{}')",
                        questionTitle, actualQuestionTitle, normalizedExpectedTitle,
                        normalizedActualTitle);
                return;
            }

            // Find text input using stable selectors based on Google Form structure
            WebElement textInput = findTextInputElement(questionElement);

            if (textInput == null) {
                log.error("No text input found for question: {}", questionTitle);
                return;
            }

            String textToEnter = getTextToEnter(questionTitle, selections);

            // Ensure element is interactive before proceeding
            wait.until(ExpectedConditions.elementToBeClickable(textInput));

            // Click on the text field first to ensure focus
            textInput.click();
            // Clear existing text if any
            textInput.clear();

            // Enter the text with delays only in human-like mode
            if (humanLike) {
                // Human-like behavior: small delays between characters
                for (char c : textToEnter.toCharArray()) {
                    textInput.sendKeys(String.valueOf(c));
                    Thread.sleep(50 + new Random().nextInt(50)); // 50-100ms delay between
                                                                 // characters
                }
            } else {
                // Fast mode: enter text immediately without delays
                textInput.sendKeys(textToEnter);
            }

            log.info("Filled text question '{}' with: {}", questionTitle, textToEnter);

        } catch (Exception e) {
            log.error("Error filling text question '{}': {}", questionTitle, e.getMessage());
            // Don't throw exception to avoid stopping the entire process
        }
    }

    /**
     * Find text input element using stable attributes based on Google Form HTML structure
     * 
     * @param questionElement The question container element
     * @return WebElement for the text input, or null if not found
     */
    private WebElement findTextInputElement(WebElement questionElement) {
        try {
            // Primary selector: Look for input with type attribute (most stable)
            List<WebElement> textInputs = questionElement
                    .findElements(By.cssSelector("input[type='text'], input[type='email']"));

            if (!textInputs.isEmpty()) {
                log.debug("Found text input using primary selector (type attribute)");
                return textInputs.get(0);
            }

            // Secondary selector: Look for textarea elements
            List<WebElement> textareas = questionElement.findElements(By.tagName("textarea"));

            if (!textareas.isEmpty()) {
                log.debug("Found textarea using secondary selector");
                return textareas.get(0);
            }

            // Tertiary selector: Look for any input element (fallback)
            textInputs = questionElement.findElements(By.tagName("input"));

            if (!textInputs.isEmpty()) {
                log.debug("Found text input using tertiary selector (fallback)");
                return textInputs.get(0);
            }

            // Quaternary selector: Look for any input element within the question (fallback)
            textInputs = questionElement.findElements(By.tagName("input"));

            if (!textInputs.isEmpty()) {
                log.debug("Found text input using quaternary selector (fallback)");
                return textInputs.get(0);
            }

            log.warn("No text input element found in question");
            return null;

        } catch (Exception e) {
            log.error("Error finding text input element: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get text to enter for the question, either from user selections or generated
     * 
     * @param questionTitle The question title
     * @param selections Map of questions to selected options
     * @return Text to enter
     */
    private String getTextToEnter(String questionTitle, Map<Question, QuestionOption> selections) {
        // Find matching question in selections
        for (Map.Entry<Question, QuestionOption> entry : selections.entrySet()) {
            if (entry.getKey().getTitle().equals(questionTitle)) {
                QuestionOption option = entry.getValue();
                if (option != null && option.getText() != null && !option.getText().isEmpty()) {
                    return option.getText();
                }
            }
        }

        // If no text from user selections, generate random text
        return generateTextByQuestionType(questionTitle);
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
            String questionTitle, String optionText, boolean humanLike)
            throws InterruptedException {
        try {
            log.info("Filling combobox question: '{}' with option: '{}'", questionTitle,
                    optionText);

            // Use the dedicated ComboboxHandler to avoid affecting other question types
            boolean success = comboboxHandler.fillComboboxQuestion(driver, questionElement,
                    questionTitle, optionText, humanLike);

            if (!success) {
                log.error("Failed to fill combobox question: '{}'", questionTitle);
            } else {
                log.info("Successfully filled combobox question: '{}'", questionTitle);
            }

        } catch (Exception e) {
            log.error("Error filling combobox question '{}': {}", questionTitle, e.getMessage());
            throw e;
        }
    }

    /**
     * Fill a multiple choice grid question
     */
    private void fillMultipleChoiceGridQuestion(WebDriver driver, WebElement questionElement,
            Question question, QuestionOption option, boolean humanLike) {
        try {
            // Use the new GridQuestionHandler for comprehensive grid question handling
            GridQuestionHandler gridHandler = new GridQuestionHandler();
            gridHandler.fillMultipleChoiceGridQuestion(driver, questionElement, question, option,
                    humanLike);
        } catch (Exception e) {
            log.error("Error filling multiple choice grid question: {}", e.getMessage());
        }
    }

    /**
     * Fill a checkbox grid question
     */
    private void fillCheckboxGridQuestion(WebDriver driver, WebElement questionElement,
            Question question, QuestionOption option, boolean humanLike) {
        try {
            // Try the new GridQuestionHandler first
            try {
                GridQuestionHandler gridHandler = new GridQuestionHandler();
                gridHandler.fillCheckboxGridQuestion(driver, questionElement, question, option,
                        humanLike);
                return;
            } catch (Exception e) {
                log.warn("GridQuestionHandler failed for checkbox grid, trying fallback method: {}",
                        e.getMessage());
            }

            // Fallback: Direct checkbox grid handling
            fillCheckboxGridQuestionDirect(driver, questionElement, question, option, humanLike);
        } catch (Exception e) {
            log.error("Error filling checkbox grid question: {}", e.getMessage());
        }
    }

    /**
     * Direct checkbox grid question handling (fallback method)
     */
    private void fillCheckboxGridQuestionDirect(WebDriver driver, WebElement questionElement,
            Question question, QuestionOption option, boolean humanLike) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
            String optionText = option.getText();

            log.info("Filling checkbox grid question directly: '{}' with option: '{}'",
                    question.getTitle(), optionText);

            // Parse the option text to extract row and option information
            CheckboxGridAnswer gridAnswer = parseCheckboxGridAnswer(optionText);

            // Find all checkbox groups (rows) in the grid using stable selectors
            List<WebElement> checkboxGroups =
                    questionElement.findElements(By.cssSelector("[role='group']"));

            // Filter out groups that don't contain checkboxes to ensure we have the right elements
            checkboxGroups = checkboxGroups.stream().filter(
                    group -> !group.findElements(By.cssSelector("[role='checkbox']")).isEmpty())
                    .collect(Collectors.toList());

            log.info("Found {} checkbox groups in checkbox grid", checkboxGroups.size());

            if (checkboxGroups.isEmpty()) {
                log.error("No checkbox groups found in checkbox grid");
                return;
            }

            // If we have specific row information, fill only that row
            if (gridAnswer.hasSpecificRow()) {
                fillSpecificCheckboxGridRow(driver, checkboxGroups, gridAnswer, wait, humanLike);
            } else {
                // Fill all rows with the same options
                fillAllCheckboxGridRows(driver, checkboxGroups, gridAnswer.getOptions(), wait,
                        humanLike);
            }

        } catch (Exception e) {
            log.error("Error filling checkbox grid question directly: {}", e.getMessage());
        }
    }

    /**
     * Parse checkbox grid answer text
     */
    private CheckboxGridAnswer parseCheckboxGridAnswer(String optionText) {
        if (optionText == null || optionText.trim().isEmpty()) {
            return new CheckboxGridAnswer(null, List.of());
        }

        String normalizedText = optionText.trim();

        // Check if it contains row information (format: "row:option1,option2")
        Pattern gridPattern = Pattern.compile("(.+?):(.+)");
        Matcher gridMatcher = gridPattern.matcher(normalizedText);

        if (gridMatcher.matches()) {
            String row = gridMatcher.group(1).trim();
            String optionsPart = gridMatcher.group(2).trim();
            List<String> options = parseMultipleOptions(optionsPart);
            return new CheckboxGridAnswer(row, options);
        }

        // No row specified, check if it contains multiple options
        if (normalizedText.contains(",")) {
            List<String> options = parseMultipleOptions(normalizedText);
            return new CheckboxGridAnswer(null, options);
        }

        // Single option for all rows
        return new CheckboxGridAnswer(null, List.of(normalizedText));
    }

    /**
     * Parse multiple options separated by commas
     */
    private List<String> parseMultipleOptions(String optionsText) {
        List<String> options = new ArrayList<>();
        String[] parts = optionsText.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                options.add(trimmed);
            }
        }
        return options;
    }

    /**
     * Fill a specific row in checkbox grid
     */
    private void fillSpecificCheckboxGridRow(WebDriver driver, List<WebElement> checkboxGroups,
            CheckboxGridAnswer gridAnswer, WebDriverWait wait, boolean humanLike) {
        try {
            String targetRow = gridAnswer.getRow();
            List<String> targetOptions = gridAnswer.getOptions();

            // Find the matching row
            WebElement targetRowElement = findCheckboxRowByLabel(checkboxGroups, targetRow);
            if (targetRowElement == null) {
                log.warn("Row '{}' not found, using first available row", targetRow);
                targetRowElement = checkboxGroups.get(0);
            }

            // Fill the row with the specified options
            fillCheckboxGridRow(driver, targetRowElement, targetOptions, wait, humanLike);

        } catch (Exception e) {
            log.error("Error filling specific checkbox grid row: {}", e.getMessage());
        }
    }

    /**
     * Fill all rows in checkbox grid with the same options
     */
    private void fillAllCheckboxGridRows(WebDriver driver, List<WebElement> checkboxGroups,
            List<String> options, WebDriverWait wait, boolean humanLike) {
        try {
            for (int i = 0; i < checkboxGroups.size(); i++) {
                WebElement row = checkboxGroups.get(i);
                String rowLabel = getRowLabel(row);
                log.info("Filling row {}: '{}' with options: {}", i + 1, rowLabel, options);

                fillCheckboxGridRow(driver, row, options, wait, humanLike);

                // Add delay between rows in human-like mode
                if (humanLike && i < checkboxGroups.size() - 1) {
                    Thread.sleep(100 + new Random().nextInt(200));
                }
            }
        } catch (Exception e) {
            log.error("Error filling all checkbox grid rows: {}", e.getMessage());
        }
    }

    /**
     * Find checkbox row by label
     */
    private WebElement findCheckboxRowByLabel(List<WebElement> rows, String targetLabel) {
        String normalizedTargetLabel = normalizeText(targetLabel);

        for (WebElement row : rows) {
            try {
                // Look for the row label in the first div with specific classes
                List<WebElement> labelElements =
                        row.findElements(By.cssSelector(".V4d7Ke.wzWPxe.OIC90c"));

                for (WebElement labelElement : labelElements) {
                    String labelText = labelElement.getText().trim();
                    if (normalizeText(labelText).equals(normalizedTargetLabel)) {
                        log.debug("Found row with label: {}", labelText);
                        return row;
                    }
                }

                // Alternative: look for any div containing the label text
                List<WebElement> allDivs = row.findElements(By.cssSelector("div"));
                for (WebElement div : allDivs) {
                    String divText = div.getText().trim();
                    if (normalizeText(divText).equals(normalizedTargetLabel)) {
                        log.debug("Found row with label (alternative method): {}", divText);
                        return row;
                    }
                }

                // Try finding text in any element within the row
                String rowText = row.getText();
                if (normalizeText(rowText).contains(normalizedTargetLabel)) {
                    log.debug("Found row containing label text: {}", targetLabel);
                    return row;
                }

            } catch (Exception e) {
                log.debug("Error processing row: {}", e.getMessage());
                // Continue searching other rows
                continue;
            }
        }

        log.warn("Row with label '{}' not found", targetLabel);
        return null;
    }

    /**
     * Get row label from checkbox group using stable selectors
     */
    private String getRowLabel(WebElement row) {
        try {
            // Look for row label using stable selectors
            // First try: find div that contains text but is not a checkbox
            List<WebElement> divs = row.findElements(By.tagName("div"));
            for (WebElement div : divs) {
                String text = div.getText().trim();
                if (!text.isEmpty()
                        && !div.findElements(By.cssSelector("[role='checkbox']")).isEmpty()) {
                    // This div contains checkboxes, skip it
                    continue;
                }
                if (!text.isEmpty()) {
                    return text;
                }
            }

            // Second try: look for any text content that's not in a checkbox
            String rowText = row.getText();
            if (!rowText.trim().isEmpty()) {
                // Extract the first line as row label
                String[] lines = rowText.split("\n");
                if (lines.length > 0) {
                    return lines[0].trim();
                }
            }

            return null;
        } catch (Exception e) {
            log.debug("Error getting row label: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fill a single checkbox grid row with the specified options
     */
    private void fillCheckboxGridRow(WebDriver driver, WebElement rowElement,
            List<String> optionTexts, WebDriverWait wait, boolean humanLike) {
        try {
            // Find all checkboxes in the row - use more specific selector
            List<WebElement> checkboxes = rowElement
                    .findElements(By.cssSelector("[role='checkbox']:not([aria-disabled='true'])"));

            if (checkboxes.isEmpty()) {
                log.warn("No checkboxes found in row");
                return;
            }

            log.debug("Found {} checkboxes in row", checkboxes.size());

            // Fill each specified option
            for (String optionText : optionTexts) {
                WebElement targetCheckbox = findCheckboxGridOption(checkboxes, optionText);

                if (targetCheckbox != null) {
                    try {
                        // Check if checkbox is already selected
                        String ariaChecked = targetCheckbox.getAttribute("aria-checked");
                        if ("true".equals(ariaChecked)) {
                            log.debug("Checkbox for option '{}' is already selected", optionText);
                            continue;
                        }

                        // Scroll to the element to ensure it's visible
                        ((JavascriptExecutor) driver).executeScript(
                                "arguments[0].scrollIntoView({block: 'center'});", targetCheckbox);

                        // Wait for element to be clickable
                        wait.until(ExpectedConditions.elementToBeClickable(targetCheckbox));

                        // Try JavaScript click first for better reliability
                        try {
                            ((JavascriptExecutor) driver).executeScript("arguments[0].click();",
                                    targetCheckbox);
                            log.debug("Clicked checkbox for option '{}' using JavaScript",
                                    optionText);
                        } catch (Exception jsError) {
                            log.debug("JavaScript click failed, trying regular click: {}",
                                    jsError.getMessage());
                            targetCheckbox.click();
                            log.debug("Clicked checkbox for option '{}' using regular click",
                                    optionText);
                        }

                        log.info("Selected checkbox option '{}' in row", optionText);

                        // Add delay in human-like mode
                        if (humanLike) {
                            Thread.sleep(50 + new Random().nextInt(100));
                        }
                    } catch (Exception e) {
                        log.error("Error clicking checkbox for option '{}': {}", optionText,
                                e.getMessage());
                    }
                } else {
                    log.warn("Checkbox option '{}' not found in row", optionText);
                }
            }

        } catch (Exception e) {
            log.error("Error filling checkbox grid row: {}", e.getMessage());
        }
    }

    /**
     * Find a checkbox by option text in checkbox grid
     */
    private WebElement findCheckboxGridOption(List<WebElement> checkboxes, String optionText) {
        String normalizedOptionText = normalizeText(optionText);

        for (WebElement checkbox : checkboxes) {
            try {
                // Try data-answer-value attribute first (Google Forms specific)
                String dataAnswerValue = checkbox.getAttribute("data-answer-value");
                if (dataAnswerValue != null
                        && normalizeText(dataAnswerValue).equals(normalizedOptionText)) {
                    log.debug("Found checkbox using data-answer-value: {}", dataAnswerValue);
                    return checkbox;
                }

                // Try aria-label attribute
                String ariaLabel = checkbox.getAttribute("aria-label");
                if (ariaLabel != null && normalizeText(ariaLabel).contains(normalizedOptionText)) {
                    log.debug("Found checkbox using aria-label: {}", ariaLabel);
                    return checkbox;
                }

                // Try text content
                String checkboxText = checkbox.getText();
                if (checkboxText != null
                        && normalizeText(checkboxText).contains(normalizedOptionText)) {
                    log.debug("Found checkbox using text content: {}", checkboxText);
                    return checkbox;
                }

                // Try finding text in child elements
                List<WebElement> childElements = checkbox.findElements(By.cssSelector("*"));
                for (WebElement child : childElements) {
                    String childText = child.getText();
                    if (childText != null
                            && normalizeText(childText).contains(normalizedOptionText)) {
                        log.debug("Found checkbox using child element text: {}", childText);
                        return checkbox;
                    }
                }

            } catch (Exception e) {
                log.debug("Error processing checkbox: {}", e.getMessage());
                // Continue to next checkbox
                continue;
            }
        }

        log.debug("No checkbox found for option: {}", optionText);
        return null;
    }

    /**
     * Normalize text for comparison
     */
    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /**
     * Fill a date question
     */
    private void fillDateQuestion(WebDriver driver, WebElement questionElement, String dateValue,
            boolean humanLike) {
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
    private void fillTimeQuestion(WebDriver driver, WebElement questionElement, String timeValue,
            boolean humanLike) {
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

    /**
     * Check if WebDriver has been quit
     * 
     * @param driver The WebDriver instance to check
     * @return True if WebDriver is quit, false otherwise
     */
    private boolean isWebDriverQuit(WebDriver driver) {
        if (driver == null) {
            return true;
        }

        try {
            // Try to get current URL - this will throw exception if driver is quit
            String currentUrl = driver.getCurrentUrl();
            return currentUrl == null;
        } catch (Exception e) {
            // If we get any exception, driver is likely quit
            log.debug("WebDriver appears to be quit: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Normalize question title for comparison by removing extra whitespace and newlines
     * 
     * @param title The question title to normalize
     * @return Normalized title
     */
    private String normalizeQuestionTitle(String title) {
        return DataProcessingUtils.normalizeQuestionTitle(title);
    }

    /**
     * Debug method to log grid structure for troubleshooting
     * 
     * @param questionElement The question container element
     * @param rowRole The role for rows ("radiogroup" or "group")
     * @param optionRole The role for options ("radio" or "checkbox")
     */
    private void debugGridStructure(WebElement questionElement, String rowRole, String optionRole) {
        try {
            log.info("=== Grid Structure Debug ===");

            // Check if questionElement is still valid
            if (questionElement == null) {
                log.error("Question element is null, cannot debug grid structure");
                return;
            }

            // Find all rows
            List<WebElement> rows =
                    questionElement.findElements(By.cssSelector("[role='" + rowRole + "']"));
            log.info("Found {} rows with role='{}'", rows.size(), rowRole);

            for (int i = 0; i < rows.size(); i++) {
                WebElement row = rows.get(i);
                String rowAriaLabel = row.getAttribute("aria-label");
                log.info("Row {}: aria-label='{}'", i + 1, rowAriaLabel);

                // Find all options in this row
                List<WebElement> options =
                        row.findElements(By.cssSelector("[role='" + optionRole + "']"));
                log.info("  Row {} has {} options", i + 1, options.size());

                for (int j = 0; j < options.size(); j++) {
                    WebElement option = options.get(j);
                    String dataValue = option.getAttribute("data-value");
                    String dataAnswerValue = option.getAttribute("data-answer-value");
                    String ariaLabel = option.getAttribute("aria-label");
                    String optionText = option.getText();

                    log.info(
                            "    Option {}: data-value='{}', data-answer-value='{}', aria-label='{}', text='{}'",
                            j + 1, dataValue, dataAnswerValue, ariaLabel, optionText);
                }
            }

            log.info("=== End Grid Structure Debug ===");
        } catch (Exception e) {
            log.error("Error in debugGridStructure: {}", e.getMessage());
        }
    }

    /**
     * Find popup container near the dropdown trigger using proximity and structure
     */
    private WebElement findPopupContainerNearDropdown(WebDriver driver, WebElement dropdownTrigger,
            WebDriverWait wait) {
        try {
            // Get dropdown position
            org.openqa.selenium.Point dropdownLocation = dropdownTrigger.getLocation();
            int dropdownX = dropdownLocation.getX();
            int dropdownY = dropdownLocation.getY();

            log.debug("Dropdown location: x={}, y={}", dropdownX, dropdownY);

            // Find all popup containers
            List<WebElement> allPopupContainers = driver.findElements(By.xpath(
                    "//div[@role='presentation' and .//div[@role='option'] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

            // Find the closest popup container to the dropdown
            WebElement closestContainer = null;
            int minDistance = Integer.MAX_VALUE;

            for (WebElement container : allPopupContainers) {
                try {
                    org.openqa.selenium.Point containerLocation = container.getLocation();
                    int containerX = containerLocation.getX();
                    int containerY = containerLocation.getY();

                    // Calculate distance (Manhattan distance for simplicity)
                    int distance =
                            Math.abs(containerX - dropdownX) + Math.abs(containerY - dropdownY);

                    log.debug("Popup container at x={}, y={}, distance={}", containerX, containerY,
                            distance);

                    if (distance < minDistance) {
                        minDistance = distance;
                        closestContainer = container;
                    }
                } catch (Exception e) {
                    log.debug("Could not get location for popup container: {}", e.getMessage());
                    continue;
                }
            }

            if (closestContainer != null) {
                log.debug("Found closest popup container at distance: {}", minDistance);
                return closestContainer;
            }

            return null;
        } catch (Exception e) {
            log.debug("Error finding popup container near dropdown: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Find popup container by question-specific attributes
     */
    private WebElement findPopupContainerByQuestionAttributes(WebDriver driver,
            WebElement dropdownTrigger, String questionTitle, WebDriverWait wait) {
        try {
            // Get the dropdown's aria-labelledby and aria-describedby
            String dropdownAriaLabelledBy = dropdownTrigger.getAttribute("aria-labelledby");
            String dropdownAriaDescribedBy = dropdownTrigger.getAttribute("aria-describedby");

            log.debug(
                    "Looking for popup container for question '{}' with aria-labelledby: {} and aria-describedby: {}",
                    questionTitle, dropdownAriaLabelledBy, dropdownAriaDescribedBy);

            // Find all popup containers
            List<WebElement> allPopupContainers = driver.findElements(By.xpath(
                    "//div[@role='presentation' and .//div[@role='option'] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

            // Look for popup container that contains options with matching attributes
            for (WebElement container : allPopupContainers) {
                try {
                    List<WebElement> options =
                            container.findElements(By.cssSelector("[role='option']"));

                    // Check if any option has matching aria-labelledby or aria-describedby
                    for (WebElement option : options) {
                        String optionAriaLabelledBy = option.getAttribute("aria-labelledby");
                        String optionAriaDescribedBy = option.getAttribute("aria-describedby");

                        boolean labelledByMatches = dropdownAriaLabelledBy != null
                                && dropdownAriaLabelledBy.equals(optionAriaLabelledBy);
                        boolean describedByMatches = dropdownAriaDescribedBy != null
                                && dropdownAriaDescribedBy.equals(optionAriaDescribedBy);

                        if (labelledByMatches || describedByMatches) {
                            log.debug(
                                    "Found popup container with matching attributes for question: {}",
                                    questionTitle);
                            return container;
                        }
                    }

                    // Additional check: Look for options that contain the question title in their
                    // attributes
                    for (WebElement option : options) {
                        String optionAriaLabel = option.getAttribute("aria-label");
                        if (optionAriaLabel != null && optionAriaLabel.contains(questionTitle)) {
                            log.debug(
                                    "Found popup container with question title in aria-label for question: {}",
                                    questionTitle);
                            return container;
                        }
                    }

                } catch (Exception e) {
                    log.debug("Error checking popup container options: {}", e.getMessage());
                    continue;
                }
            }

            return null;
        } catch (Exception e) {
            log.debug("Error finding popup container by question attributes: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Find popup container by jsname attribute (Google Forms specific)
     */
    private WebElement findPopupContainerByJsname(WebDriver driver, WebElement dropdownTrigger,
            WebDriverWait wait) {
        try {
            // Get the jsname from the dropdown trigger
            String dropdownJsname = dropdownTrigger.getAttribute("jsname");
            if (dropdownJsname == null || dropdownJsname.trim().isEmpty()) {
                log.debug("Dropdown has no jsname attribute");
                return null;
            }

            log.debug("Looking for popup container with jsname: {}", dropdownJsname);

            // Enhanced popup container finding with multiple strategies
            // Strategy 1: Look for popup container with specific jsname V68bde (Google Forms
            // specific)
            try {
                WebElement popupContainer =
                        wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                                "//div[@jsname='V68bde' and @role='presentation' and .//div[@role='option'] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]")));
                log.debug("Found popup container with jsname V68bde");
                return popupContainer;
            } catch (Exception e) {
                log.debug("Could not find popup container with jsname V68bde: {}", e.getMessage());
            }

            // Strategy 2: Look for popup container with stable attributes (no dynamic classes)
            try {
                WebElement popupContainer =
                        wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                                "//div[@role='presentation' and .//div[@role='option'] and .//span[@jsslot] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]")));
                log.debug("Found popup container with jsslot span structure");
                return popupContainer;
            } catch (Exception e) {
                log.debug("Could not find popup container with jsslot span structure: {}",
                        e.getMessage());
            }

            // Strategy 3: Look for popup container with data-value attributes
            try {
                WebElement popupContainer =
                        wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                                "//div[@role='presentation' and .//div[@role='option' and @data-value] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]")));
                log.debug("Found popup container with data-value options");
                return popupContainer;
            } catch (Exception e) {
                log.debug("Could not find popup container with data-value options: {}",
                        e.getMessage());
            }

            // Strategy 4: Look for any visible popup container with options
            try {
                List<WebElement> popupContainers = driver.findElements(By.xpath(
                        "//div[@role='presentation' and .//div[@role='option'] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

                // Find the most recently appeared popup container (likely the one we just opened)
                if (!popupContainers.isEmpty()) {
                    log.debug("Found {} popup containers, using the first visible one",
                            popupContainers.size());
                    return popupContainers.get(0);
                }
            } catch (Exception e) {
                log.debug("Could not find any popup containers: {}", e.getMessage());
            }

            return null;
        } catch (Exception e) {
            log.debug("Error finding popup container by jsname: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Find popup container by matching aria-labelledby and aria-describedby attributes
     */
    private WebElement findPopupContainerByAriaLabelledBy(WebDriver driver,
            WebElement dropdownTrigger, WebDriverWait wait) {
        try {
            // Get the aria-labelledby and aria-describedby from the dropdown trigger
            String dropdownAriaLabelledBy = dropdownTrigger.getAttribute("aria-labelledby");
            String dropdownAriaDescribedBy = dropdownTrigger.getAttribute("aria-describedby");

            if (dropdownAriaLabelledBy == null || dropdownAriaLabelledBy.trim().isEmpty()) {
                log.debug("Dropdown has no aria-labelledby attribute");
                return null;
            }

            log.debug(
                    "Looking for popup container with aria-labelledby: {} and aria-describedby: {}",
                    dropdownAriaLabelledBy, dropdownAriaDescribedBy);

            // Find all popup containers
            List<WebElement> allPopupContainers = driver.findElements(By.xpath(
                    "//div[@role='presentation' and .//div[@role='option'] and not(contains(@style, 'display: none')) and not(contains(@style, 'visibility: hidden'))]"));

            // Look for popup container that contains options with matching attributes
            for (WebElement container : allPopupContainers) {
                try {
                    List<WebElement> options =
                            container.findElements(By.cssSelector("[role='option']"));
                    for (WebElement option : options) {
                        String optionAriaLabelledBy = option.getAttribute("aria-labelledby");
                        String optionAriaDescribedBy = option.getAttribute("aria-describedby");

                        // Check if aria-labelledby matches
                        boolean labelledByMatches =
                                dropdownAriaLabelledBy.equals(optionAriaLabelledBy);

                        // Check if aria-describedby matches (if both have it)
                        boolean describedByMatches = false;
                        if (dropdownAriaDescribedBy != null && optionAriaDescribedBy != null) {
                            describedByMatches =
                                    dropdownAriaDescribedBy.equals(optionAriaDescribedBy);
                        }

                        if (labelledByMatches || describedByMatches) {
                            log.debug(
                                    "Found popup container with matching attributes - labelledBy: {}, describedBy: {}",
                                    labelledByMatches, describedByMatches);
                            return container;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error checking popup container options: {}", e.getMessage());
                    continue;
                }
            }

            return null;
        } catch (Exception e) {
            log.debug("Error finding popup container by aria-labelledby: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fallback strategy for filling combobox when popup container is not found This method tries
     * multiple strategies to find and click options
     */
    private void fillComboboxWithFallbackStrategy(WebDriver driver, WebElement dropdownTrigger,
            String questionTitle, String optionText, boolean humanLike) {
        try {
            log.info("Using fallback strategy for combobox question: {}", questionTitle);

            WebDriverWait wait = new WebDriverWait(driver, java.time.Duration.ofSeconds(10));

            // Ensure dropdown is expanded
            String ariaExpanded = dropdownTrigger.getAttribute("aria-expanded");
            if (!"true".equals(ariaExpanded)) {
                log.warn("Dropdown not expanded in fallback strategy, trying to expand it");
                // Try multiple click strategies
                try {
                    // Strategy 1: Regular click
                    dropdownTrigger.click();
                    Thread.sleep(500);
                } catch (Exception e1) {
                    log.debug("Regular click failed: {}", e1.getMessage());
                    try {
                        // Strategy 2: JavaScript click
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();",
                                dropdownTrigger);
                        Thread.sleep(500);
                    } catch (Exception e2) {
                        log.debug("JavaScript click failed: {}", e2.getMessage());
                        try {
                            // Strategy 3: Focus and click
                            dropdownTrigger.sendKeys(Keys.SPACE);
                            Thread.sleep(500);
                        } catch (Exception e3) {
                            log.debug("Space key failed: {}", e3.getMessage());
                        }
                    }
                }

                // Check if dropdown expanded
                ariaExpanded = dropdownTrigger.getAttribute("aria-expanded");
                log.debug("Dropdown aria-expanded after fallback expansion: {}", ariaExpanded);
            }

            // Wait a bit for dropdown to expand
            Thread.sleep(1000); // Increased wait time for dropdown to expand

            // Strategy 1: Try to find options directly within the dropdown trigger
            List<WebElement> options =
                    dropdownTrigger.findElements(By.cssSelector("[role='option']"));
            log.info("Strategy 1: Found {} options within dropdown trigger", options.size());

            if (!options.isEmpty()) {
                // Try to select the option
                boolean found = false;
                String normalizedOptionText = normalize(optionText);

                for (WebElement option : options) {
                    try {
                        String dataValue = normalize(option.getAttribute("data-value"));
                        String spanText = "";

                        try {
                            WebElement span = option.findElement(By.tagName("span"));
                            spanText = normalize(span.getText());
                        } catch (Exception ignore) {
                        }

                        if ((!dataValue.isEmpty() && dataValue.equals(normalizedOptionText))
                                || (!spanText.isEmpty() && spanText.equals(normalizedOptionText))) {

                            try {
                                ((JavascriptExecutor) driver).executeScript("arguments[0].click();",
                                        option);
                                log.info("Selected option '{}' using fallback strategy 1",
                                        optionText);
                                found = true;
                                break;
                            } catch (Exception e) {
                                log.debug("Fallback strategy 1 click failed: {}", e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Error processing option in fallback strategy 1: {}",
                                e.getMessage());
                    }
                }

                if (found) {
                    return;
                }
            }

            // Strategy 2: Try to find options in the entire page
            log.info("Strategy 2: Looking for options in entire page");
            List<WebElement> allOptions = driver.findElements(By.cssSelector("[role='option']"));
            log.info("Found {} total options in page", allOptions.size());

            String normalizedOptionText = normalize(optionText);
            for (WebElement option : allOptions) {
                try {
                    String dataValue = normalize(option.getAttribute("data-value"));
                    String spanText = "";

                    try {
                        WebElement span = option.findElement(By.tagName("span"));
                        spanText = normalize(span.getText());
                    } catch (Exception ignore) {
                    }

                    if ((!dataValue.isEmpty() && dataValue.equals(normalizedOptionText))
                            || (!spanText.isEmpty() && spanText.equals(normalizedOptionText))) {

                        try {
                            ((JavascriptExecutor) driver).executeScript("arguments[0].click();",
                                    option);
                            log.info("Selected option '{}' using fallback strategy 2", optionText);
                            return;
                        } catch (Exception e) {
                            log.debug("Fallback strategy 2 click failed: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error processing option in fallback strategy 2: {}", e.getMessage());
                }
            }

            log.error("All fallback strategies failed for combobox question: {}", questionTitle);

        } catch (Exception e) {
            log.error("Error in fallback strategy for combobox question '{}': {}", questionTitle,
                    e.getMessage());
        }
    }

    /**
     * Find option element using stable attributes (no dynamic classes)
     */
    private WebElement findOptionByStableAttributes(WebElement popupContainer, String optionText) {
        try {
            String normalizedOptionText = normalize(optionText);
            List<WebElement> options =
                    popupContainer.findElements(By.cssSelector("[role='option']"));

            for (WebElement option : options) {
                try {
                    // Strategy 1: Check data-value attribute (most stable)
                    String dataValue = normalize(option.getAttribute("data-value"));
                    if (!dataValue.isEmpty() && dataValue.equals(normalizedOptionText)) {
                        log.debug("Found option by data-value: '{}'", dataValue);
                        return option;
                    }

                    // Strategy 2: Check aria-label attribute
                    String ariaLabel = normalize(option.getAttribute("aria-label"));
                    if (!ariaLabel.isEmpty() && ariaLabel.equals(normalizedOptionText)) {
                        log.debug("Found option by aria-label: '{}'", ariaLabel);
                        return option;
                    }

                    // Strategy 3: Check span text with jsslot attribute
                    try {
                        WebElement span = option.findElement(By.cssSelector("span[jsslot]"));
                        String spanText = normalize(span.getText());
                        if (!spanText.isEmpty() && spanText.equals(normalizedOptionText)) {
                            log.debug("Found option by span text: '{}'", spanText);
                            return option;
                        }
                    } catch (Exception e) {
                        // Span not found, continue
                    }

                    // Strategy 4: Check any text content in the option
                    String optionTextContent = normalize(option.getText());
                    if (!optionTextContent.isEmpty()
                            && optionTextContent.equals(normalizedOptionText)) {
                        log.debug("Found option by text content: '{}'", optionTextContent);
                        return option;
                    }

                } catch (Exception e) {
                    log.debug("Error checking option: {}", e.getMessage());
                    continue;
                }
            }

            return null;
        } catch (Exception e) {
            log.error("Error finding option by stable attributes: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Enhanced method to select an option from a popup container with better reliability
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

            // Strategy 1: Try clicking the span element with jsslot attribute
            try {
                WebElement spanElement = targetOption.findElement(By.cssSelector("span[jsslot]"));
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", spanElement);
                log.info("Selected option '{}' for question: {} using span click", optionText,
                        questionTitle);
                clickSuccess = true;
            } catch (Exception spanClickException) {
                log.debug("Span click failed: {}", spanClickException.getMessage());
            }

            // Strategy 2: Try JavaScript click on the option element
            if (!clickSuccess) {
                try {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();",
                            targetOption);
                    log.info("Selected option '{}' for question: {} using JavaScript click",
                            optionText, questionTitle);
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
                    log.info("Selected option '{}' for question: {} using regular click",
                            optionText, questionTitle);
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

                // Verify the selection was successful
                if (verifyOptionSelection(driver, optionText, questionTitle)) {
                    log.info("Option selection verified successfully for question: {}",
                            questionTitle);
                    return true;
                } else {
                    log.warn("Option selection verification failed for question: {}",
                            questionTitle);
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
    private boolean verifyOptionSelection(WebDriver driver, String optionText,
            String questionTitle) {
        try {
            // Wait a bit for the selection to be reflected in the UI
            Thread.sleep(300);

            // Look for the dropdown trigger that should now show the selected option
            List<WebElement> dropdownTriggers =
                    driver.findElements(By.cssSelector("[role='listbox']"));

            for (WebElement trigger : dropdownTriggers) {
                try {
                    // Check if the trigger contains the selected option text
                    String triggerText = trigger.getText();
                    String normalizedTriggerText = normalize(triggerText);
                    String normalizedOptionText = normalize(optionText);

                    if (normalizedTriggerText.contains(normalizedOptionText)) {
                        log.debug(
                                "Option selection verified: trigger text '{}' contains selected option '{}'",
                                triggerText, optionText);
                        return true;
                    }

                    // Also check aria-label attribute
                    String ariaLabel = trigger.getAttribute("aria-label");
                    if (ariaLabel != null && normalize(ariaLabel).contains(normalizedOptionText)) {
                        log.debug(
                                "Option selection verified: aria-label '{}' contains selected option '{}'",
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
     * Wait for popup container to be fully loaded and ready for interaction
     */
    private boolean waitForPopupReady(WebDriver driver, WebElement popupContainer,
            WebDriverWait wait) {
        try {
            // Wait for popup to be visible
            wait.until(ExpectedConditions.visibilityOf(popupContainer));

            // Wait for options to be loaded
            wait.until(
                    ExpectedConditions.presenceOfElementLocated(By.cssSelector("[role='option']")));

            // Wait a bit more for any animations to complete
            Thread.sleep(300);

            // Verify that options are actually clickable
            List<WebElement> options =
                    popupContainer.findElements(By.cssSelector("[role='option']"));
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
     * Normalize string for comparison (unicode normalization, trim, lowercase)
     */
    private String normalize(String s) {
        if (s == null)
            return "";
        return java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFC)
                .toLowerCase();
    }

    /**
     * Retry mechanism for finding FillRequest by ID
     */
    private FillRequest findFillRequestWithRetry(UUID fillRequestId) {
        int maxRetries = 3;
        int retryCount = 0;
        Exception lastException = null;
        while (retryCount < maxRetries) {
            try {
                java.util.Optional<FillRequest> optional =
                        fillRequestRepository.findById(fillRequestId);
                if (optional.isPresent()) {
                    log.info("Found FillRequest {} on attempt {}", fillRequestId, retryCount + 1);
                    return optional.get();
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("Error finding FillRequest {} (attempt {}): {}", fillRequestId,
                        retryCount + 1, e.getMessage());
            }
            retryCount++;
            if (retryCount < maxRetries) {
                try {
                    Thread.sleep(1000L * retryCount); // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("Failed to find FillRequest {} after {} attempts", fillRequestId, maxRetries);
        if (lastException != null) {
            throw new ResourceNotFoundException("FillRequest", "id", fillRequestId);
        } else {
            throw new ResourceNotFoundException("FillRequest", "id", fillRequestId);
        }
    }

    /**
     * Execute a single form fill task and update counters
     */
    private void executeFormFillTask(UUID fillRequestId, String link,
            Map<Question, QuestionOption> plan, boolean humanLike, AtomicInteger successCount,
            AtomicInteger failCount) {
        try {
            boolean success = executeFormFill(fillRequestId, link, plan, humanLike);
            if (success) {
                successCount.incrementAndGet();
                log.info("Form fill task succeeded for fillRequest {}", fillRequestId);
            } else {
                failCount.incrementAndGet();
                log.warn("Form fill task failed for fillRequest {}", fillRequestId);
            }
        } catch (Exception e) {
            failCount.incrementAndGet();
            log.error("Exception during form fill task for fillRequest {}: {}", fillRequestId,
                    e.getMessage(), e);
        }
    }

    /**
     * Find a question element from a map by normalized title
     */
    private WebElement findQuestionElementFromMap(String questionTitle,
            Map<String, WebElement> questionMap, UUID fillRequestId) {
        if (questionTitle == null || questionMap == null) {
            log.warn(
                    "findQuestionElementFromMap: questionTitle or questionMap is null for fillRequest {}",
                    fillRequestId);
            return null;
        }
        String normalizedTitle = questionTitle.replace("*", "").trim().toLowerCase();
        WebElement element = questionMap.get(normalizedTitle);
        if (element != null) {
            log.debug("Found question element for '{}' in fillRequest {}", questionTitle,
                    fillRequestId);
        } else {
            log.warn("Question element not found for '{}' in fillRequest {}", questionTitle,
                    fillRequestId);
        }
        return element;
    }

    /**
     * Generate random text by question type or title using TestDataEnum or fallback
     */
    private String generateTextByQuestionType(String questionTitle) {
        try {
            String lower = questionTitle.toLowerCase();
            if (lower.contains("email")) {
                String value = TestDataEnum.getRandomEmail();
                log.debug("Generated email for '{}': {}", questionTitle, value);
                return value;
            } else if (lower.contains("tên") || lower.contains("name")) {
                String value = TestDataEnum.getRandomName();
                log.debug("Generated name for '{}': {}", questionTitle, value);
                return value;
            } else if (lower.contains("số điện thoại") || lower.contains("phone")) {
                String value = TestDataEnum.getRandomPhoneNumber();
                log.debug("Generated phone for '{}': {}", questionTitle, value);
                return value;
            } else if (lower.contains("địa chỉ") || lower.contains("address")) {
                String value = TestDataEnum.getRandomAddress();
                log.debug("Generated address for '{}': {}", questionTitle, value);
                return value;
            } else if (lower.contains("góp ý") || lower.contains("feedback")
                    || lower.contains("nhận xét") || lower.contains("ý kiến")) {
                String value = TestDataEnum.getRandomFeedback();
                log.debug("Generated feedback for '{}': {}", questionTitle, value);
                return value;
            }
            // Fallback: generate a random string
            String fallback = "AutoGen-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            log.debug("Generated fallback text for '{}': {}", questionTitle, fallback);
            return fallback;
        } catch (Exception e) {
            log.error("Error generating text for '{}': {}", questionTitle, e.getMessage());
            return "AutoGen-Error";
        }
    }

    /**
     * Build a map of normalized question titles to their WebElement containers.
     */
    private Map<String, WebElement> buildQuestionMap(List<WebElement> questionElements) {
        Map<String, WebElement> questionMap = new HashMap<>();
        for (WebElement element : questionElements) {
            try {
                List<WebElement> headings =
                        element.findElements(By.cssSelector("[role='heading']"));
                if (!headings.isEmpty()) {
                    String title = headings.get(0).getText().replace("*", "").trim();
                    if (!title.isEmpty()) {
                        String normalizedTitle = title.toLowerCase();
                        questionMap.put(normalizedTitle, element);
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return questionMap;
    }
}


