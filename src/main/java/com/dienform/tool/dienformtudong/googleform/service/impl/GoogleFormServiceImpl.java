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
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import com.dienform.tool.dienformtudong.fillrequest.service.FillRequestCounterService;
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
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SesstionExecution;
import com.dienform.tool.dienformtudong.surveyexecution.repository.SessionExecutionRepository;
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
    private final FillRequestCounterService fillRequestCounterService;
    private final FormRepository formRepository;
    private final AnswerDistributionRepository answerDistributionRepository;

    private final SessionExecutionRepository sessionExecutionRepository;

    private final ComboboxHandler comboboxHandler;
    private final QuestionRepository questionRepository;
    private final GridQuestionHandler gridQuestionHandler;

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

    // Deprecated caches (avoid storing WebElement; not reused across sessions)
    // private final Map<String, List<WebElement>> questionElementsCache = new
    // ConcurrentHashMap<>();
    // private final Map<String, WebElement> questionMappingCache = new ConcurrentHashMap<>();

    // OPTIMIZED: Thread pool for parallel question processing
    private final ExecutorService questionProcessingExecutor = Executors.newFixedThreadPool(5);

    // New: Cache By locators per form and question (safe across sessions)
    private final Map<UUID, Map<UUID, By>> formLocatorCache = new ConcurrentHashMap<>();

    // Track active WebDriver instances per fill request to guarantee shutdown
    private final Map<UUID, Set<WebDriver>> activeDriversByFillRequest = new ConcurrentHashMap<>();

    // Pool of user-provided 'Other' texts per fillRequest and question
    private final Map<UUID, Map<UUID, Queue<String>>> otherTextPoolsByFillRequest =
            new ConcurrentHashMap<>();
    private final ThreadLocal<UUID> currentFillRequestIdHolder = new ThreadLocal<>();
    private final Map<UUID, Map<UUID, java.util.List<String>>> otherTextBaseByFillRequest =
            new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, AtomicInteger>> otherTextIndexByFillRequest =
            new ConcurrentHashMap<>();

    // New: Local map for data-fill API submissions to carry Other text per question
    private final ThreadLocal<Map<UUID, String>> dataFillOtherTextByQuestion = new ThreadLocal<>();

    // Realtime gateway to notify UI on status changes
    private final com.dienform.realtime.FillRequestRealtimeGateway realtimeGateway;
    private final com.dienform.common.util.CurrentUserUtil currentUserUtil;

    /**
     * Cleanup method to clear caches and shutdown executor
     */
    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up GoogleFormService resources...");

        // Force close any remaining WebDriver instances
        try {
            for (Map.Entry<UUID, Set<WebDriver>> entry : activeDriversByFillRequest.entrySet()) {
                Set<WebDriver> drivers = entry.getValue();
                if (drivers != null) {
                    for (WebDriver d : drivers.toArray(new WebDriver[0])) {
                        shutdownDriver(d);
                    }
                    drivers.clear();
                }
            }
        } catch (Exception e) {
            log.warn("Error during WebDriver cleanup: {}", e.getMessage());
        } finally {
            activeDriversByFillRequest.clear();
        }

        // Clear caches
        formLocatorCache.clear();

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
     * Pre-download and setup ChromeDriver once at startup to avoid per-run delays
     */
    @jakarta.annotation.PostConstruct
    public void initWebDriverBinary() {
        try {
            io.github.bonigarcia.wdm.WebDriverManager.chromedriver().setup();
            log.info("ChromeDriver binary initialized at startup");
        } catch (Exception e) {
            log.warn("Failed to initialize ChromeDriver at startup: {}", e.getMessage());
        }
    }

    /**
     * Clear all caches manually
     */
    public void clearCaches() {
        log.info("Clearing all caches...");
        formLocatorCache.clear();
        log.info("All caches cleared");
    }

    /**
     * Clear caches for a specific fill request
     */
    public void clearCachesForFillRequest(UUID fillRequestId) {
        String cacheKey = fillRequestId.toString();
        log.info("Clearing caches for fillRequest: {}", fillRequestId);

        // Per-fillRequest caches removed; rely on TTL cleanup of formLocatorCache

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
    public int fillForm(UUID fillRequestId) {
        log.info("Starting automated form filling for request ID: {}", fillRequestId);

        // CRITICAL FIX: Add retry mechanism for finding fill request
        final FillRequest fillRequest = findFillRequestWithRetry(fillRequestId);

        // Validate fill request status before starting
        if (!(com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.PENDING
                .equals(fillRequest.getStatus())
                || com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.IN_PROCESS
                        .equals(fillRequest.getStatus()))) {
            log.warn("Fill request {} is not in valid state to start. Current status: {}",
                    fillRequestId, fillRequest.getStatus());
            return 0;
        }

        // Avoid aggressive per-request cache clears; TTL scheduler will clean caches periodically

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

        // Prepare 'Other' text pools for radio/checkbox per question
        try {
            Map<UUID, Queue<String>> poolPerQuestion = new ConcurrentHashMap<>();
            Map<UUID, java.util.List<String>> basePerQuestion = new ConcurrentHashMap<>();
            Map<UUID, AtomicInteger> indexPerQuestion = new ConcurrentHashMap<>();
            for (AnswerDistribution d : distributions) {
                try {
                    if (d.getQuestion() == null || d.getOption() == null)
                        continue;
                    Question q = d.getQuestion();
                    QuestionOption opt = d.getOption();
                    String optValue = opt.getValue();
                    String val = d.getValueString();
                    if (optValue != null && "__other_option__".equalsIgnoreCase(optValue)
                            && val != null && !val.trim().isEmpty()) {
                        Queue<String> qPool = poolPerQuestion.computeIfAbsent(q.getId(),
                                k -> new ConcurrentLinkedQueue<>());
                        // Base list stores unique user values for round-robin reuse
                        java.util.List<String> base = basePerQuestion.computeIfAbsent(q.getId(),
                                k -> new java.util.ArrayList<>());
                        base.add(val.trim());
                        // Queue will be populated lazily from base list during consumption
                        indexPerQuestion.putIfAbsent(q.getId(), new AtomicInteger(0));
                    }
                } catch (Exception ignore) {
                }
            }
            otherTextPoolsByFillRequest.put(fillRequestId, poolPerQuestion);
            otherTextBaseByFillRequest.put(fillRequestId, basePerQuestion);
            otherTextIndexByFillRequest.put(fillRequestId, indexPerQuestion);
            log.info("Prepared 'Other' text pools for {} questions", poolPerQuestion.size());
        } catch (Exception e) {
            log.warn("Failed to prepare 'Other' text pools: {}", e.getMessage());
        }

        // Create a record in fill form sessionExecute
        final SesstionExecution sessionExecute = SesstionExecution.builder().formId(form.getId())
                .fillRequestId(fillRequestId).startTime(LocalDateTime.now())
                .totalExecutions(fillRequest.getSurveyCount()).successfulExecutions(0)
                .failedExecutions(0).status(FormStatusEnum.PROCESSING).build();

        sessionExecutionRepository.save(sessionExecute);

        // Update request status to RUNNING using direct update to avoid entity overwrite of
        // counters
        try {
            fillRequestRepository.updateStatus(fillRequest.getId(),
                    com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.IN_PROCESS);
        } catch (Exception ignore) {
            // fallback to existing method
            updateFillRequestStatus(fillRequest,
                    com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.IN_PROCESS
                            .name());
        }

        // Ensure user is in room and send initial updates
        ensureUserInRoom(fillRequest);

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
            currentFillRequestIdHolder.set(fillRequestId);

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

                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        executeFormFillTask(fillRequestId, link, plan, fillRequest.isHumanLike(),
                                successCount, failCount);
                    }, executor).whenComplete((v, t) -> {
                        int processed = totalProcessed.incrementAndGet();
                        if (processed == fillRequest.getSurveyCount()) {
                            // All tasks finished execution, update final status based on DB state
                            updateFinalStatus(fillRequest, sessionExecute, successCount.get(),
                                    failCount.get());
                        }
                    });

                    futures.add(future);

                } catch (Exception e) {
                    log.error("Error during form filling: {}", e.getMessage(), e);
                    failCount.incrementAndGet();
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
                    if (!executor.awaitTermination(180, TimeUnit.SECONDS)) {
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

            // Force-close any remaining drivers for this fill request as a safety net
            forceCloseAllDriversForFillRequest(fillRequestId);

            // Let TTL scheduler clear caches periodically instead of per-request
            currentFillRequestIdHolder.remove();
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
    public boolean submitFormWithBrowser(UUID fillRequestId, UUID formId, String formUrl,
            Map<String, String> formData) {
        try {
            // Load questions for this form to ensure title/type/additionalData are present
            List<com.dienform.tool.dienformtudong.question.entity.Question> questionsForForm =
                    questionRepository.findByFormIdOrderByPosition(formId);
            Map<UUID, com.dienform.tool.dienformtudong.question.entity.Question> questionsById =
                    new HashMap<>();
            for (com.dienform.tool.dienformtudong.question.entity.Question q : questionsForForm) {
                if (q.getId() != null) {
                    questionsById.put(q.getId(), q);
                }
            }

            // Convert formData to Map<Question, QuestionOption>
            Map<Question, QuestionOption> selections = new HashMap<>();
            Map<UUID, String> localOtherText = new HashMap<>();
            for (Map.Entry<String, String> entry : formData.entrySet()) {
                UUID qid = UUID.fromString(entry.getKey());
                Question question = questionsById.get(qid);
                if (question == null) {
                    // Fallback to single fetch if not preloaded (should be rare)
                    question = questionRepository.findById(qid).orElse(null);
                }
                if (question == null) {
                    log.warn("Question not found for id {} in submitFormWithBrowser", qid);
                    continue;
                }

                String raw = entry.getValue() == null ? "" : entry.getValue().trim();
                int dashIdx = raw.lastIndexOf('-');
                String main = dashIdx > 0 ? raw.substring(0, dashIdx).trim() : raw;
                String other = dashIdx > 0 ? raw.substring(dashIdx + 1).trim() : null;
                if (other != null && !other.isEmpty()) {
                    localOtherText.put(qid, other);
                }

                QuestionOption option = new QuestionOption();
                option.setText(main);
                option.setQuestion(question);

                selections.put(question, option);
            }
            // expose per-submission other text map
            dataFillOtherTextByQuestion.set(localOtherText);

            // Use existing executeFormFill method with formId as cache key
            return executeFormFill(fillRequestId, formId, formUrl, selections, true);
        } catch (Exception e) {
            log.error("Error submitting form: {}", e.getMessage(), e);
            return false;
        } finally {
            dataFillOtherTextByQuestion.remove();
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

            if (com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.IN_PROCESS
                    .equals(fillRequest.getStatus())) {
                fillRequest.setStatus(
                        com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.PENDING);
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

            // Only update if status actually changed
            String currentStatus = current.getStatus() != null ? current.getStatus().name() : null;
            if (!status.equals(currentStatus)) {
                current.setStatus(
                        com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum
                                .valueOf(status));
                fillRequestRepository.save(current);
                log.info("Updated fill request {} status to: {}", current.getId(), status);

                // Emit realtime update only when status changes
                emitSingleUpdate(current);
            } else {
                log.debug("Fill request {} status unchanged: {}", current.getId(), status);
            }
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
            // Determine final status based on persisted completedSurvey first
            FillRequest current = fillRequestRepository.findById(fillRequest.getId()).orElseThrow(
                    () -> new ResourceNotFoundException("Fill Request", "id", fillRequest.getId()));

            boolean allPersistedCompleted =
                    current.getCompletedSurvey() >= current.getSurveyCount();

            String finalStatus;
            if (failCount > 0) {
                finalStatus =
                        com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.FAILED
                                .name();
            } else if (allPersistedCompleted) {
                finalStatus =
                        com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.COMPLETED
                                .name();
            } else {
                // Tasks finished but DB increments not fully reflected yet
                finalStatus =
                        com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.IN_PROCESS
                                .name();
            }

            // Update fill request status (this will emit the event if status changed)
            updateFillRequestStatus(fillRequest, finalStatus);

            // Best-effort: atomic update session execution to avoid optimistic locking
            try {
                int affected = sessionExecutionRepository.updateFinalState(sessionExecute.getId(),
                        LocalDateTime.now(), successCount, failCount, FormStatusEnum.COMPLETED);
                if (affected == 0) {
                    // Fallback: try update the latest session by fillRequestId
                    SesstionExecution latest = sessionExecutionRepository
                            .findTopByFillRequestIdOrderByStartTimeDesc(fillRequest.getId());
                    if (latest != null) {
                        int affected2 = sessionExecutionRepository.updateFinalState(latest.getId(),
                                LocalDateTime.now(), successCount, failCount,
                                FormStatusEnum.COMPLETED);
                        if (affected2 == 0) {
                            log.warn("No session execution rows updated for id {} (fallback)",
                                    latest.getId());
                        }
                    } else {
                        log.warn(
                                "No session execution found for fillRequest {} to update final state",
                                fillRequest.getId());
                    }
                }
            } catch (Exception sessionUpdateError) {
                log.warn("Failed to update session execution final state: {}",
                        sessionUpdateError.getMessage());
            }

            log.info("Fill request {} completed. Success: {}, Failed: {}, Final status: {}",
                    fillRequest.getId(), successCount, failCount, finalStatus);
        } catch (Exception e) {
            log.error("Failed to compute/update final status: {}", e.getMessage(), e);
            // On fatal error, mark FAILED and notify UI + leave user room
            try {
                FillRequest fr = fillRequestRepository.findById(fillRequest.getId()).orElse(null);
                if (fr != null && fr.getForm() != null) {
                    fr.setStatus(
                            com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.FAILED);
                    fillRequestRepository.save(fr);
                    com.dienform.realtime.dto.FillRequestUpdateEvent evt =
                            com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                                    .formId(fr.getForm().getId().toString())
                                    .requestId(fr.getId().toString())
                                    .status(com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum.FAILED
                                            .name())
                                    .completedSurvey(fr.getCompletedSurvey())
                                    .surveyCount(fr.getSurveyCount())
                                    .updatedAt(java.time.Instant.now().toString()).build();

                    // Get current user ID if available
                    String userId = null;
                    try {
                        userId = currentUserUtil.getCurrentUserIdIfPresent().map(UUID::toString)
                                .orElse(null);
                    } catch (Exception ignore) {
                        log.debug("Failed to get current user ID: {}", ignore.getMessage());
                    }

                    // Use centralized emit method with deduplication
                    realtimeGateway.emitUpdateWithUser(fr.getForm().getId().toString(), evt,
                            userId);

                    // Leave user room if user ID is available
                    if (userId != null) {
                        try {
                            realtimeGateway.leaveUserFormRoom(userId,
                                    fr.getForm().getId().toString());
                        } catch (Exception ignore2) {
                            log.debug("Failed to leave user room: {}", ignore2.getMessage());
                        }
                    }
                }
            } catch (Exception ignore) {
                log.debug("Failed to handle fatal error: {}", ignore.getMessage());
            }
        }
    }

    /**
     * Centralized emit method to avoid duplicates
     */
    private void emitSingleUpdate(FillRequest fillRequest) {
        try {
            com.dienform.realtime.dto.FillRequestUpdateEvent event =
                    com.dienform.realtime.dto.FillRequestUpdateEvent.builder()
                            .formId(fillRequest.getForm().getId().toString())
                            .requestId(fillRequest.getId().toString())
                            .status(fillRequest.getStatus().name())
                            .completedSurvey(fillRequest.getCompletedSurvey())
                            .surveyCount(fillRequest.getSurveyCount())
                            .updatedAt(java.time.Instant.now().toString()).build();

            // Get current user ID if available
            String userId = null;
            try {
                userId = currentUserUtil.getCurrentUserIdIfPresent().map(UUID::toString)
                        .orElse(null);
            } catch (Exception ignore) {
                log.debug("Failed to get current user ID: {}", ignore.getMessage());
            }

            // Use centralized emit method with deduplication
            realtimeGateway.emitUpdateWithUser(fillRequest.getForm().getId().toString(), event,
                    userId);

        } catch (Exception emitErr) {
            log.warn("Failed to emit realtime status update: {}", emitErr.getMessage());
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

        // Tạo mapping theo vị trí cho text questions để đảm bảo tính nhất quán
        Map<Integer, Map<UUID, String>> positionMapping =
                buildPositionMapping(distributionsByQuestion);
        int maxPositionCount =
                positionMapping.isEmpty() ? 0 : Collections.max(positionMapping.keySet()) + 1;

        for (int i = 0; i < serveyCount; i++) {
            Map<Question, QuestionOption> plan = new HashMap<>();

            // Sử dụng cùng position cho tất cả text questions trong lần điền này
            int currentPosition = maxPositionCount > 0 ? i % maxPositionCount : 0;
            Map<UUID, String> currentPositionValues = positionMapping.get(currentPosition);

            // For each question, select an option based on distributions
            for (Map.Entry<Question, List<AnswerDistribution>> entry : distributionsByQuestion
                    .entrySet()) {
                Question question = entry.getKey();
                List<AnswerDistribution> questionDistributions = entry.getValue();

                // Unified handling: free-text and date/time use valueString with position mapping;
                // others use distribution
                String type;
                try {
                    // Defensive: access fields inside try to avoid LazyInitializationException
                    type = question.getType() == null ? "" : question.getType().toLowerCase();
                } catch (org.hibernate.LazyInitializationException lie) {
                    // Reload managed entity if proxy got detached
                    try {
                        Question managed =
                                questionRepository.findById(question.getId()).orElse(null);
                        type = managed != null && managed.getType() != null
                                ? managed.getType().toLowerCase()
                                : "";
                        question = managed != null ? managed : question;
                    } catch (Exception ex) {
                        type = "";
                    }
                }
                boolean isFreeText =
                        type.equals("text") || type.equals("email") || type.equals("textarea")
                                || type.equals("short_answer") || type.equals("paragraph");
                boolean isDate = type.equals("date");
                boolean isTime = type.equals("time");

                if (isFreeText || isDate || isTime) {
                    String value = getTextValueForPosition(question.getId(), currentPositionValues,
                            questionDistributions, type);

                    // If no value from position mapping, generate based on question title
                    if (value == null) {
                        value = generateTextByQuestionType(question.getTitle());
                    }

                    QuestionOption textOption = new QuestionOption();
                    textOption.setQuestion(question);
                    textOption.setText(value);
                    plan.put(question, textOption);
                } else {
                    // radio/checkbox/combobox/grid use option distributions
                    QuestionOption selectedOption =
                            selectOptionBasedOnDistribution(questionDistributions);
                    if (selectedOption != null) {
                        plan.put(question, selectedOption);
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
    private boolean executeFormFill(UUID fillRequestId, UUID formId, String formUrl,
            Map<Question, QuestionOption> selections, boolean humanLike) {
        WebDriver driver = null;
        try {
            log.info("Starting to fill form with {} questions", selections.size());
            long startTime = System.currentTimeMillis();

            // Open browser with optimized settings
            log.info("Opening browser...");
            driver = openBrowser(formUrl, humanLike);
            // Track this driver for the current fill request to ensure force shutdown later
            try {
                Set<WebDriver> set = activeDriversByFillRequest.computeIfAbsent(fillRequestId,
                        k -> java.util.Collections
                                .newSetFromMap(new ConcurrentHashMap<WebDriver, Boolean>()));
                set.add(driver);
            } catch (Exception e) {
                log.warn("Failed to register WebDriver for tracking: {}", e.getMessage());
            }
            long browserOpenTime = System.currentTimeMillis();
            log.info("Browser opened in {}ms", browserOpenTime - startTime);

            // OPTIMIZED: Use shorter timeout for element operations
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15)); // Reduced from
                                                                                    // 30s to 15s

            // No need to pre-fetch and map all question elements; use per-question By locators

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

                    WebElement questionElement = resolveQuestionElement(driver, formUrl, question);
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
                    // If this question used 'Other', fill its text now.
                    try {
                        if (fillSuccess && option != null) {
                            boolean needsOtherFill = false;
                            if (option.getValue() != null
                                    && option.getValue().equalsIgnoreCase("__other_option__")) {
                                needsOtherFill = true;
                            }
                            // Checkbox path: if the token list contains __other_option__
                            if (!needsOtherFill
                                    && "checkbox".equalsIgnoreCase(question.getType())) {
                                String text = option.getText();
                                if (text != null && text.contains("__other_option__")) {
                                    needsOtherFill = true;
                                }
                            }
                            if (needsOtherFill) {
                                fillOtherTextForQuestion(driver, questionElement, question.getId(),
                                        fillRequestId, humanLike);
                            }
                        }
                    } catch (Exception ignore) {
                    }
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
                    shutdownDriver(driver);
                } finally {
                    // Remove from active set
                    try {
                        Set<WebDriver> set = activeDriversByFillRequest.get(fillRequestId);
                        if (set != null) {
                            set.remove(driver);
                        }
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    /**
     * Force-close all tracked WebDriver instances for a specific fill request.
     */
    private void forceCloseAllDriversForFillRequest(UUID fillRequestId) {
        try {
            Set<WebDriver> drivers = activeDriversByFillRequest.get(fillRequestId);
            if (drivers != null) {
                for (WebDriver d : drivers.toArray(new WebDriver[0])) {
                    shutdownDriver(d);
                }
                drivers.clear();
            }
        } catch (Exception e) {
            log.warn("Error force closing drivers for fillRequest {}: {}", fillRequestId,
                    e.getMessage());
        } finally {
            activeDriversByFillRequest.remove(fillRequestId);
        }
    }

    /**
     * Safely shutdown a WebDriver: try to close all windows then quit, with fallbacks.
     */
    private void shutdownDriver(WebDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            try {
                for (String handle : driver.getWindowHandles()) {
                    try {
                        driver.switchTo().window(handle);
                        driver.close();
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception ignore) {
            }
            try {
                driver.quit();
                log.info("WebDriver quit invoked");
            } catch (Exception e) {
                log.warn("driver.quit() failed: {}", e.getMessage());
                try {
                    driver.close();
                } catch (Exception closeException) {
                    log.error("driver.close() also failed: {}", closeException.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Unexpected error during driver shutdown: {}", e.getMessage());
        }
    }

    /**
     * Find question WebElement using pre-computed metadata from DB additionalData. - Prefer liIndex
     * to index into filteredQuestionElements for O(1) - Fallback to containerXPath if present
     */
    private WebElement findQuestionByAdditionalData(WebDriver driver, Question question,
            List<WebElement> unused) {
        try {
            Map<String, String> additionalData = question.getAdditionalData();
            if (additionalData != null) {
                String liIndexStr = additionalData.get("liIndex");
                if (liIndexStr != null) {
                    try {
                        int liIndex = Integer.parseInt(liIndexStr);
                        List<WebElement> allListItems =
                                driver.findElements(By.cssSelector("div[role='listitem']"));
                        if (liIndex >= 0 && liIndex < allListItems.size()) {
                            return allListItems.get(liIndex);
                        }
                    } catch (NumberFormatException ignore) {
                    }
                }

                String containerXPath = additionalData.get("containerXPath");
                if (containerXPath != null && !containerXPath.isBlank()) {
                    try {
                        return driver.findElement(By.xpath(containerXPath));
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /**
     * Build a stable By locator for a question using saved additionalData
     */
    private By buildLocatorForQuestion(Question question) {
        Map<String, String> add = question.getAdditionalData();
        if (add != null) {
            String li = add.get("liIndex");
            if (li != null) {
                try {
                    int i = Integer.parseInt(li);
                    return By.xpath("(//div[@role='listitem'])[" + (i + 1) + "]");
                } catch (NumberFormatException ignore) {
                }
            }
            String x = add.get("containerXPath");
            if (x != null && !x.isBlank()) {
                return By.xpath(x);
            }
            String t = add.get("headingNormalized");
            if (t != null && !t.isBlank()) {
                return By.xpath(
                        "//div[@role='listitem'][.//div[@role='heading' and normalize-space()=\""
                                + t.replace("\"", "\\\"") + "\"]]");
            }
        }
        String t = question.getTitle() == null ? "" : question.getTitle();
        return By.xpath("//div[@role='listitem'][.//div[@role='heading' and normalize-space()=\""
                + t.replace("\"", "\\\"") + "\"]]");
    }

    /**
     * Resolve question element via cached By locator. Fallback to rebuilt locator if needed. Throws
     * MappingException with details when not found.
     */
    private WebElement resolveQuestionElement(WebDriver driver, String formUrl, Question question) {
        UUID formId = question.getForm() != null ? question.getForm().getId() : null;
        if (formId == null) {
            // Fallback: try to locate by freshly built locator
            By by = buildLocatorForQuestion(question);
            try {
                return new WebDriverWait(driver, Duration.ofSeconds(10))
                        .until(ExpectedConditions.presenceOfElementLocated(by));
            } catch (Exception e) {
                throw buildMappingException(question, "No formId on question; locator failed", e);
            }
        }

        Map<UUID, By> perForm =
                formLocatorCache.computeIfAbsent(formId, k -> new ConcurrentHashMap<>());
        By by = perForm.computeIfAbsent(question.getId(), k -> buildLocatorForQuestion(question));

        try {
            return new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.presenceOfElementLocated(by));
        } catch (Exception first) {
            // Rebuild fallback and try once
            By fallback = buildLocatorForQuestion(question);
            if (!fallback.toString().equals(by.toString())) {
                perForm.put(question.getId(), fallback);
            }
            try {
                return new WebDriverWait(driver, Duration.ofSeconds(5))
                        .until(ExpectedConditions.presenceOfElementLocated(fallback));
            } catch (Exception second) {
                throw buildMappingException(question, "Locator failed (primary and fallback)",
                        second);
            }
        }
    }

    private com.dienform.tool.dienformtudong.exception.MappingException buildMappingException(
            Question question, String reason, Exception cause) {
        Map<String, Object> details = new HashMap<>();
        details.put("formId", question.getForm() != null ? question.getForm().getId() : null);
        details.put("questionId", question.getId());
        details.put("questionTitle", question.getTitle());
        Map<String, String> add = question.getAdditionalData();
        if (add != null) {
            details.put("liIndex", add.get("liIndex"));
            details.put("containerXPath", add.get("containerXPath"));
            details.put("headingNormalized", add.get("headingNormalized"));
        }
        details.put("reason", reason);
        return new com.dienform.tool.dienformtudong.exception.MappingException(
                "Failed to map question element", details);
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
        // options.addArguments("--window-size=1920,1080");

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

        // Faster page initialization: do not block for full load
        options.setPageLoadStrategy(org.openqa.selenium.PageLoadStrategy.EAGER);

        // Disable images to speed up load
        java.util.Map<String, Object> prefs = new java.util.HashMap<>();
        prefs.put("profile.managed_default_content_settings.images", 2);
        options.setExperimentalOption("prefs", prefs);

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
        WebDriver driver = new ChromeDriver(options);
        log.info("ChromeDriver created successfully");

        if (driver == null) {
            throw new RuntimeException("Failed to create ChromeDriver");
        }

        // Set optimized timeouts: short pageLoadTimeout and zero implicit wait
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(10));
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);

        // Create wait object with shorter timeout
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15)); // Reduced from 180s
                                                                                // to 15s

        // Navigate to form URL with retry mechanism
        log.info("Navigating to form URL: {}", formUrl);
        driver.get(formUrl);

        // Wait for form to be ready (minimal wait)
        try {
            log.info("Waiting for form to load completely...");

            // Wait for question containers to be present
            wait.until(ExpectedConditions
                    .presenceOfElementLocated(By.xpath("//div[@role='listitem']")));
            log.info("Form ready: question containers present");

        } catch (Exception e) {
            log.warn("Waiting for form containers failed: {}. Proceeding.", e.getMessage());
        }

        // Proceed immediately

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

            boolean selected = false;
            // Exact match by data-value
            for (WebElement radio : radioOptions) {
                try {
                    String dataValue = radio.getAttribute("data-value");
                    if (dataValue != null && dataValue.trim().equals(optionText.trim())) {
                        wait.until(ExpectedConditions.elementToBeClickable(radio));
                        radio.click();
                        log.info("Selected radio option (exact match): {}", optionText);
                        selected = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }

            // Fallback: aria-label exact
            if (!selected) {
                for (WebElement radio : radioOptions) {
                    try {
                        String ariaLabel = radio.getAttribute("aria-label");
                        if (ariaLabel != null && ariaLabel.trim().equals(optionText.trim())) {
                            wait.until(ExpectedConditions.elementToBeClickable(radio));
                            radio.click();
                            log.info("Selected radio option (aria-label match): {}", optionText);
                            selected = true;
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            // Fallback: visible label text via ancestor label span[dir=auto]
            if (!selected) {
                for (WebElement radio : radioOptions) {
                    try {
                        String spanText = "";
                        try {
                            // Prefer label descendants which are more stable than sibling chains
                            WebElement span = radio
                                    .findElement(By.xpath("ancestor::label//span[@dir='auto']"));
                            spanText = span.getText().trim();
                        } catch (Exception ignored) {
                        }
                        if (!spanText.isEmpty() && (spanText.equals(optionText)
                                || spanText.equalsIgnoreCase(optionText))) {
                            wait.until(ExpectedConditions.elementToBeClickable(radio));
                            radio.click();
                            log.info("Selected radio option (label text): {}", optionText);
                            selected = true;
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            // Fallback: explicitly select Google 'Other' radio by data-value when asked for its
            // label
            if (!selected) {
                try {
                    for (WebElement radio : radioOptions) {
                        String dataValue = radio.getAttribute("data-value");
                        if ("__other_option__".equalsIgnoreCase(dataValue)) {
                            wait.until(ExpectedConditions.elementToBeClickable(radio));
                            radio.click();
                            log.info("Selected radio option (__other_option__ fallback) for '{}'",
                                    optionText);
                            selected = true;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            if (!selected) {
                log.warn("No matching radio option found for: {}", optionText);
                return;
            }

            // Defer filling 'Other' text to higher-level handler with questionId context

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

            // First, try to match the entire input as a single option before splitting by
            // separators
            // This allows selecting options whose labels contain commas or pipes.
            String fullInput = optionText == null ? "" : optionText.trim();
            if (!fullInput.isEmpty()) {
                boolean selectedByFullMatch = trySelectCheckboxOptionByExactFullString(wait,
                        checkboxOptions, fullInput, expectedTitle);
                if (selectedByFullMatch) {
                    // Selected by full exact match; do not split further to avoid unintended
                    // selections
                    return;
                }
            }

            // Support multi-select values: e.g., "3|5" or "A|B,C". Split by , or |
            String[] desiredParts = optionText.split("[,|]");
            java.util.Set<String> desired = new java.util.HashSet<>();
            for (String p : desiredParts) {
                String t = p.trim();
                if (!t.isEmpty())
                    desired.add(t);
            }

            int selectedCount = 0;
            boolean otherSelected = false;
            // Try matching each desired token across strategies
            for (String token : desired) {
                boolean matched = false;

                // Try exact match with data-answer-value
                for (WebElement checkbox : checkboxOptions) {
                    String dataValue = checkbox.getAttribute("data-answer-value");
                    if (dataValue != null && (dataValue.trim().equals(token)
                            || dataValue.trim().equalsIgnoreCase(token))) {
                        wait.until(ExpectedConditions.elementToBeClickable(checkbox));
                        checkbox.click();
                        log.info("Checked checkbox option (data-value) '{}' for question '{}'",
                                token, expectedTitle);
                        if ("__other_option__".equalsIgnoreCase(dataValue)
                                || checkbox.getAttribute("data-other-checkbox") != null) {
                            otherSelected = true;
                        }
                        matched = true;
                        selectedCount++;
                        break;
                    }
                }
                if (matched)
                    continue;

                // Try aria-label
                for (WebElement checkbox : checkboxOptions) {
                    String ariaLabel = checkbox.getAttribute("aria-label");
                    if (ariaLabel != null && (ariaLabel.trim().equals(token)
                            || ariaLabel.trim().equalsIgnoreCase(token))) {
                        wait.until(ExpectedConditions.elementToBeClickable(checkbox));
                        checkbox.click();
                        log.info("Checked checkbox option (aria-label) '{}' for question '{}'",
                                token, expectedTitle);
                        if ("__other_option__"
                                .equalsIgnoreCase(checkbox.getAttribute("data-answer-value"))
                                || checkbox.getAttribute("data-other-checkbox") != null) {
                            otherSelected = true;
                        }
                        matched = true;
                        selectedCount++;
                        break;
                    }
                }
                if (matched)
                    continue;

                // Try text content match
                for (WebElement checkbox : checkboxOptions) {
                    String checkboxText = checkbox.getText().trim();
                    if (checkboxText.isEmpty()) {
                        try {
                            WebElement span = checkbox.findElement(
                                    By.xpath(".//following-sibling::div//span[@dir='auto']"));
                            checkboxText = span.getText().trim();
                        } catch (Exception ignored) {
                        }
                    }
                    if (!checkboxText.isEmpty() && (checkboxText.equals(token)
                            || checkboxText.equalsIgnoreCase(token))) {
                        wait.until(ExpectedConditions.elementToBeClickable(checkbox));
                        checkbox.click();
                        log.info("Checked checkbox option (text content) '{}' for question '{}'",
                                token, expectedTitle);
                        if ("__other_option__"
                                .equalsIgnoreCase(checkbox.getAttribute("data-answer-value"))
                                || checkbox.getAttribute("data-other-checkbox") != null
                                || checkboxText.equalsIgnoreCase("Mục khác:")) {
                            otherSelected = true;
                        }
                        matched = true;
                        selectedCount++;
                        break;
                    }
                }

                if (!matched) {
                    log.warn("Checkbox option token '{}' not found for question '{}'", token,
                            expectedTitle);
                }
            }

            if (selectedCount == 0) {
                log.warn("No checkbox options could be selected for question '{}' from input '{}'",
                        expectedTitle, optionText);
            }

            // Defer filling 'Other' text to higher-level handler with questionId context

        } catch (Exception e) {
            log.error("Error filling checkbox question '{}': {}", optionText, e.getMessage());
        }
    }

    /**
     * Attempt to select a checkbox option using the entire provided input as an exact label match.
     * This supports option labels that contain separators like commas or pipes without breaking
     * existing multi-select behavior.
     */
    private boolean trySelectCheckboxOptionByExactFullString(WebDriverWait wait,
            List<WebElement> checkboxOptions, String fullLabel, String questionTitle) {
        String target = fullLabel.trim();
        for (WebElement checkbox : checkboxOptions) {
            try {
                // data-answer-value exact match
                String dataValue = checkbox.getAttribute("data-answer-value");
                if (dataValue != null && (dataValue.trim().equals(target)
                        || dataValue.trim().equalsIgnoreCase(target))) {
                    wait.until(ExpectedConditions.elementToBeClickable(checkbox));
                    checkbox.click();
                    log.info(
                            "Checked checkbox option (full match data-value) '{}' for question '{}'",
                            target, questionTitle);
                    return true;
                }

                // aria-label exact match
                String ariaLabel = checkbox.getAttribute("aria-label");
                if (ariaLabel != null && (ariaLabel.trim().equals(target)
                        || ariaLabel.trim().equalsIgnoreCase(target))) {
                    wait.until(ExpectedConditions.elementToBeClickable(checkbox));
                    checkbox.click();
                    log.info(
                            "Checked checkbox option (full match aria-label) '{}' for question '{}'",
                            target, questionTitle);
                    return true;
                }

                // text content exact match (including sibling span commonly used for label text)
                String checkboxText = checkbox.getText() == null ? "" : checkbox.getText().trim();
                if (checkboxText.isEmpty()) {
                    try {
                        WebElement span = checkbox.findElement(
                                By.xpath(".//following-sibling::div//span[@dir='auto']"));
                        checkboxText = span.getText().trim();
                    } catch (Exception ignored) {
                    }
                }
                if (!checkboxText.isEmpty()
                        && (checkboxText.equals(target) || checkboxText.equalsIgnoreCase(target))) {
                    wait.until(ExpectedConditions.elementToBeClickable(checkbox));
                    checkbox.click();
                    log.info("Checked checkbox option (full match text) '{}' for question '{}'",
                            target, questionTitle);
                    return true;
                }
            } catch (Exception inner) {
                log.debug("Error during full-string checkbox match: {}", inner.getMessage());
            }
        }
        return false;
    }

    /**
     * Find the 'Other' input for the currently selected 'Other' option in this question.
     */
    private WebElement findOtherTextInput(WebElement questionElement) {
        try {
            // Primary: exact aria-label
            List<WebElement> exact = questionElement.findElements(
                    By.cssSelector("input[type='text'][aria-label='Câu trả lời khác']"));
            for (WebElement e : exact) {
                if (e.isDisplayed() && e.isEnabled()) {
                    return e;
                }
            }

            // Secondary: any input with aria-label containing 'khác' (case-insensitive)
            List<WebElement> anyAria =
                    questionElement.findElements(By.cssSelector("input[type='text'][aria-label]"));
            for (WebElement e : anyAria) {
                try {
                    String aria = e.getAttribute("aria-label");
                    if (aria != null && aria.toLowerCase().contains("khác") && e.isDisplayed()
                            && e.isEnabled()) {
                        return e;
                    }
                } catch (Exception ignored) {
                }
            }

            // Tertiary: from label text 'Mục khác:' → following input
            try {
                WebElement span = questionElement.findElement(
                        By.xpath(".//span[@dir='auto' and normalize-space(.)='Mục khác:']"));
                WebElement container =
                        span.findElement(By.xpath("ancestor::label/following-sibling::div"));
                WebElement input = container.findElement(By.xpath(".//input[@type='text']"));
                if (input != null && input.isDisplayed() && input.isEnabled()) {
                    return input;
                }
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String generateAutoOtherText() {
        String uuid = java.util.UUID.randomUUID().toString().replace("-", "");
        String prefix = uuid.length() >= 8 ? uuid.substring(0, 8) : uuid;
        return "autogen_" + prefix;
    }

    /**
     * Fill the 'Other' text input using user-provided value for the specific question.
     */
    private void fillOtherTextForQuestion(WebDriver driver, WebElement questionElement,
            UUID questionId, UUID fillRequestId, boolean humanLike) {
        try {
            WebElement input = findOtherTextInput(questionElement);
            if (input == null)
                return;

            // If already filled, don't overwrite
            try {
                String existing = input.getAttribute("value");
                if (existing != null && !existing.trim().isEmpty()) {
                    return;
                }
            } catch (Exception ignored) {
            }

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(ExpectedConditions.elementToBeClickable(input));

            input.click();
            try {
                input.clear();
            } catch (Exception ignored) {
            }

            String sampleText = getOtherTextForPosition(questionId, fillRequestId);

            if (sampleText == null) {
                sampleText = generateAutoOtherText();
            }

            if (humanLike) {
                for (char c : sampleText.toCharArray()) {
                    input.sendKeys(String.valueOf(c));
                    try {
                        Thread.sleep(40 + new Random().nextInt(60));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } else {
                input.sendKeys(sampleText);
            }
            log.info("Filled 'Other' text input for question {} with value: {}", questionId,
                    sampleText);
        } catch (Exception e) {
            log.debug("Failed to fill 'Other' input for question {}: {}", questionId,
                    e.getMessage());
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
            // Use the injected GridQuestionHandler for comprehensive grid question handling
            gridQuestionHandler.fillMultipleChoiceGridQuestion(driver, questionElement, question,
                    option, humanLike);
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
            // Try the injected GridQuestionHandler first
            try {
                gridQuestionHandler.fillCheckboxGridQuestion(driver, questionElement, question,
                        option, humanLike);
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

        // Check if it contains row information (format: "row:option1,option2").
        // Use greedy left group to split on the last colon to support row labels containing ':'
        Pattern gridPattern = Pattern.compile("(.+):(.+)");
        Matcher gridMatcher = gridPattern.matcher(normalizedText);

        if (gridMatcher.matches()) {
            String row = gridMatcher.group(1).trim();
            String optionsPart = gridMatcher.group(2).trim();
            List<String> options = parseMultipleOptions(optionsPart);
            return new CheckboxGridAnswer(row, options);
        }

        // No row specified, check if it contains multiple options (support "," and "|")
        if (normalizedText.contains(",") || normalizedText.contains("|")) {
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
        String[] parts = optionsText.split("[,|]");
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
     * Fill all rows in checkbox grid with randomized options to avoid same answer for all rows
     */
    private void fillAllCheckboxGridRows(WebDriver driver, List<WebElement> checkboxGroups,
            List<String> options, WebDriverWait wait, boolean humanLike) {
        try {
            // Get available column options from the question structure
            List<String> availableOptions = extractAvailableColumnOptions(checkboxGroups);

            if (availableOptions.isEmpty()) {
                log.warn("No column options found, using original options");
                // Fallback to original behavior
                fillAllCheckboxGridRowsOriginal(driver, checkboxGroups, options, wait, humanLike);
                return;
            }

            log.debug("Available column options: {}", availableOptions);

            for (int i = 0; i < checkboxGroups.size(); i++) {
                WebElement row = checkboxGroups.get(i);
                String rowLabel = getRowLabel(row);

                // For each row, randomly select from available options
                // But give higher probability to the original options
                List<String> selectedOptions =
                        selectRandomOptionsWithBias(options, availableOptions);

                log.info("Filling row {}: '{}' with randomized options: {}", i + 1, rowLabel,
                        selectedOptions);

                fillCheckboxGridRow(driver, row, selectedOptions, wait, humanLike);

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
     * Original method for filling all rows with same options (fallback)
     */
    private void fillAllCheckboxGridRowsOriginal(WebDriver driver, List<WebElement> checkboxGroups,
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
     * Extract available column options from checkbox groups
     */
    private List<String> extractAvailableColumnOptions(List<WebElement> checkboxGroups) {
        List<String> availableOptions = new ArrayList<>();

        if (checkboxGroups.isEmpty()) {
            return availableOptions;
        }

        // Get options from the first row as reference
        WebElement firstRow = checkboxGroups.get(0);
        List<WebElement> checkboxes = firstRow.findElements(By.cssSelector("[role='checkbox']"));

        for (WebElement checkbox : checkboxes) {
            try {
                // Try data-answer-value attribute first (Google Forms specific)
                String dataAnswerValue = checkbox.getAttribute("data-answer-value");
                if (dataAnswerValue != null && !dataAnswerValue.trim().isEmpty()) {
                    availableOptions.add(dataAnswerValue.trim());
                    continue;
                }

                // Try aria-label attribute
                String ariaLabel = checkbox.getAttribute("aria-label");
                if (ariaLabel != null && !ariaLabel.trim().isEmpty()) {
                    availableOptions.add(ariaLabel.trim());
                    continue;
                }

                // Try text content
                String text = checkbox.getText().trim();
                if (!text.isEmpty()) {
                    availableOptions.add(text);
                }
            } catch (Exception e) {
                log.debug("Error extracting option from checkbox: {}", e.getMessage());
            }
        }

        return availableOptions;
    }

    /**
     * Select random options with bias towards the original options
     */
    private List<String> selectRandomOptionsWithBias(List<String> originalOptions,
            List<String> availableOptions) {
        if (availableOptions.size() <= originalOptions.size()) {
            return originalOptions;
        }

        List<String> selectedOptions = new ArrayList<>();
        Random random = new Random();

        for (String originalOption : originalOptions) {
            // 70% chance to use original option, 30% chance to use random option
            if (random.nextDouble() < 0.7) {
                selectedOptions.add(originalOption);
            } else {
                // Remove already selected options and original option from available options
                List<String> remainingOptions = new ArrayList<>(availableOptions);
                remainingOptions.removeAll(selectedOptions);
                remainingOptions.remove(originalOption);

                if (remainingOptions.isEmpty()) {
                    selectedOptions.add(originalOption);
                } else {
                    selectedOptions
                            .add(remainingOptions.get(random.nextInt(remainingOptions.size())));
                }
            }
        }

        return selectedOptions;
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
                    // Fallback: if option is numeric, treat it as 1-based column index
                    String token = optionText != null ? optionText.trim() : "";
                    if (token.matches("\\d+")) {
                        int idx = Integer.parseInt(token);
                        if (idx >= 1 && idx <= checkboxes.size()) {
                            WebElement byIndex = checkboxes.get(idx - 1);
                            try {
                                String ariaChecked = byIndex.getAttribute("aria-checked");
                                if (!"true".equals(ariaChecked)) {
                                    ((JavascriptExecutor) driver).executeScript(
                                            "arguments[0].scrollIntoView({block: 'center'});",
                                            byIndex);
                                    wait.until(ExpectedConditions.elementToBeClickable(byIndex));
                                    try {
                                        ((JavascriptExecutor) driver)
                                                .executeScript("arguments[0].click();", byIndex);
                                    } catch (Exception jsErr) {
                                        byIndex.click();
                                    }
                                    log.info("Selected checkbox option by index {} (fallback)",
                                            idx);
                                    if (humanLike) {
                                        Thread.sleep(50 + new Random().nextInt(100));
                                    }
                                } else {
                                    log.debug("Checkbox by index {} already selected", idx);
                                }
                            } catch (Exception clickErr) {
                                log.debug("Failed to click checkbox by index {}: {}", idx,
                                        clickErr.getMessage());
                            }
                            continue;
                        }
                    }
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
                // Use findByIdWithAllData to avoid LazyInitializationException
                java.util.Optional<FillRequest> optional =
                        fillRequestRepository.findByIdWithAllData(fillRequestId);
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
            // fetch formId once for accuracy
            UUID formIdSafe = null;
            try {
                FillRequest frTmp = fillRequestRepository.findById(fillRequestId).orElse(null);
                if (frTmp != null && frTmp.getForm() != null) {
                    formIdSafe = frTmp.getForm().getId();
                }
            } catch (Exception ignore) {
            }
            boolean success = executeFormFill(fillRequestId, formIdSafe, link, plan, humanLike);
            if (success) {
                successCount.incrementAndGet();
                log.info("Form fill task succeeded for fillRequest {}", fillRequestId);
                try {
                    // Use dedicated counter service with REQUIRES_NEW transaction and retry logic
                    boolean incrementSuccess = fillRequestCounterService
                            .incrementCompletedSurveyWithDelay(fillRequestId);
                    if (!incrementSuccess) {
                        log.warn(
                                "Failed to increment completedSurvey for {} after retries (may have reached limit)",
                                fillRequestId);
                    }
                    // Emit progress update after increment so FE reflects latest completedSurvey
                    emitProgressUpdate(fillRequestId);
                } catch (Exception e) {
                    log.error("Failed to increment completedSurvey for {}: {}", fillRequestId,
                            e.getMessage());
                }
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
     * Ensure current user is in the form room and send initial updates
     */
    private void ensureUserInRoom(FillRequest fillRequest) {
        try {
            currentUserUtil.getCurrentUserIdIfPresent().ifPresent(uid -> {
                String userId = uid.toString();
                String formId = fillRequest.getForm().getId().toString();

                // Ensure user joins the room
                realtimeGateway.ensureUserJoinedFormRoom(userId, formId);

                // Send bulk state update
                realtimeGateway.emitBulkStateForUser(userId, formId);

                // Send current fill request update
                com.dienform.realtime.dto.FillRequestUpdateEvent evt =
                        com.dienform.realtime.dto.FillRequestUpdateEvent.builder().formId(formId)
                                .requestId(fillRequest.getId().toString())
                                .status(fillRequest.getStatus().name())
                                .completedSurvey(fillRequest.getCompletedSurvey())
                                .surveyCount(fillRequest.getSurveyCount())
                                .updatedAt(java.time.Instant.now().toString()).build();
                realtimeGateway.emitUpdateForUser(userId, formId, evt);

                log.debug("Ensured user {} is in room for form {} and sent initial updates", userId,
                        formId);
            });
        } catch (Exception e) {
            log.warn("Failed to ensure user in room: {}", e.getMessage());
        }
    }

    /**
     * Emit progress update to both form room and user-specific room
     */
    private void emitProgressUpdate(UUID fillRequestId) {
        try {
            FillRequest currentAfter = fillRequestRepository.findById(fillRequestId).orElse(null);
            if (currentAfter != null && currentAfter.getForm() != null) {
                // Use centralized emit method to avoid duplicates
                emitSingleUpdate(currentAfter);
                log.debug("Emitted progress update for fillRequest: {} - {}/{}", fillRequestId,
                        currentAfter.getCompletedSurvey(), currentAfter.getSurveyCount());
            }
        } catch (Exception e) {
            log.warn("Failed to emit progress update: {}", e.getMessage());
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
            // Fallback: reuse the unified autogen format
            String fallback = generateAutoOtherText();
            log.debug("Generated fallback text for '{}': {}", questionTitle, fallback);
            return fallback;
        } catch (Exception e) {
            log.error("Error generating text for '{}': {}", questionTitle, e.getMessage());
            return generateAutoOtherText();
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

    /**
     * Build position mapping for text questions to ensure consistency
     * 
     * @param distributionsByQuestion Map of questions to their distributions
     * @return Map of position index to question values
     */
    private Map<Integer, Map<UUID, String>> buildPositionMapping(
            Map<Question, List<AnswerDistribution>> distributionsByQuestion) {

        Map<Integer, Map<UUID, String>> positionMapping = new HashMap<>();

        // Process each question's distributions
        for (Map.Entry<Question, List<AnswerDistribution>> entry : distributionsByQuestion
                .entrySet()) {
            Question question = entry.getKey();
            List<AnswerDistribution> distributions = entry.getValue();

            // Only process text questions
            if (isTextQuestion(question.getType())) {
                for (AnswerDistribution dist : distributions) {
                    if (dist.getValueString() != null && !dist.getValueString().trim().isEmpty()) {
                        // Use positionIndex if available, otherwise default to 0
                        int position =
                                dist.getPositionIndex() != null ? dist.getPositionIndex() : 0;

                        positionMapping.computeIfAbsent(position, k -> new HashMap<>())
                                .put(question.getId(), dist.getValueString().trim());
                    }
                }
            }
        }

        log.debug("Built position mapping with {} positions", positionMapping.size());
        return positionMapping;
    }

    /**
     * Get text value for a specific position and question
     * 
     * @param questionId The question ID
     * @param currentPositionValues Map of current position values
     * @param questionDistributions List of distributions for the question
     * @param questionType The type of question
     * @return The text value to use
     */
    private String getTextValueForPosition(UUID questionId, Map<UUID, String> currentPositionValues,
            List<AnswerDistribution> questionDistributions, String questionType) {

        // First, try to get value from position mapping
        if (currentPositionValues != null && currentPositionValues.containsKey(questionId)) {
            String value = currentPositionValues.get(questionId);
            log.debug("Using position-mapped value for question {}: {}", questionId, value);
            return value;
        }

        // Fallback to original round-robin logic if no position mapping
        List<String> userValues =
                questionDistributions.stream().map(AnswerDistribution::getValueString)
                        .filter(v -> v != null && !v.trim().isEmpty()).map(String::trim)
                        .collect(Collectors.toList());

        if (!userValues.isEmpty()) {
            // Use simple round-robin for backward compatibility
            int index = (int) (System.currentTimeMillis() % userValues.size());
            String value = userValues.get(index);
            log.debug("Using round-robin value for question {}: {}", questionId, value);
            return value;
        }

        // Generate default value based on question type
        if ("date".equals(questionType)) {
            java.time.LocalDate today = java.time.LocalDate.now();
            return today.toString();
        } else if ("time".equals(questionType)) {
            return "09:00";
        } else {
            // This will be handled by the calling method which has access to question title
            return null;
        }
    }

    /**
     * Check if a question type is a text question
     * 
     * @param questionType The question type to check
     * @return True if it's a text question
     */
    private boolean isTextQuestion(String questionType) {
        if (questionType == null)
            return false;
        String type = questionType.toLowerCase();
        return type.equals("text") || type.equals("email") || type.equals("textarea")
                || type.equals("short_answer") || type.equals("paragraph") || type.equals("date")
                || type.equals("time");
    }

    /**
     * Get other text for a specific position and question
     * 
     * @param questionId The question ID
     * @param fillRequestId The fill request ID
     * @return The other text value to use
     */
    private String getOtherTextForPosition(UUID questionId, UUID fillRequestId) {
        String sampleText = null;

        // Prefer per-submission explicit 'Other' text provided via data-fill API
        try {
            Map<UUID, String> local = dataFillOtherTextByQuestion.get();
            if (local != null && questionId != null) {
                String v = local.get(questionId);
                if (v != null && !v.trim().isEmpty()) {
                    sampleText = v.trim();
                }
            }
        } catch (Exception ignore) {
        }

        // Fallback to position-based mapping
        try {
            UUID fillId = fillRequestId != null ? fillRequestId : currentFillRequestIdHolder.get();
            if (fillId != null && questionId != null) {
                Map<UUID, Queue<String>> pools = otherTextPoolsByFillRequest.get(fillId);
                if (pools != null) {
                    Queue<String> q = pools.get(questionId);
                    if (q != null) {
                        String v = q.poll();
                        if (v != null && !v.trim().isEmpty()) {
                            sampleText = v.trim();
                        }
                    }
                }
                if (sampleText == null) {
                    Map<UUID, java.util.List<String>> baseMap =
                            otherTextBaseByFillRequest.get(fillId);
                    Map<UUID, AtomicInteger> idxMap = otherTextIndexByFillRequest.get(fillId);
                    if (baseMap != null && idxMap != null) {
                        java.util.List<String> base = baseMap.get(questionId);
                        if (base != null && !base.isEmpty()) {
                            AtomicInteger ai =
                                    idxMap.computeIfAbsent(questionId, k -> new AtomicInteger(0));
                            int i = Math.abs(ai.getAndIncrement());
                            sampleText = base.get(i % base.size());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return sampleText;
    }
}


