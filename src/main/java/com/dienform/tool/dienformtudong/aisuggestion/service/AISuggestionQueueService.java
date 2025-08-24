package com.dienform.tool.dienformtudong.aisuggestion.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AISuggestionRequest;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AnswerAttributesRequest;
import com.dienform.tool.dienformtudong.aisuggestion.dto.response.AnswerAttributesResponse;
import com.dienform.tool.dienformtudong.aisuggestion.entity.AISuggestionRequestEntity;
import com.dienform.tool.dienformtudong.aisuggestion.entity.AISuggestionRequestEntity.AISuggestionStatus;
import com.dienform.tool.dienformtudong.aisuggestion.repository.AISuggestionRequestRepository;
import com.dienform.tool.dienformtudong.aisuggestion.service.impl.AISuggestionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for managing AI Suggestion requests with priority-based queue system Prevents API rate
 * limiting and ensures fair processing
 */
@Service
@Slf4j
public class AISuggestionQueueService {

    @Autowired
    private AISuggestionRequestRepository repository;

    @Autowired
    private AISuggestionServiceImpl aiSuggestionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${ai.suggestion.queue.max-size:50}")
    private int maxQueueSize;

    @Value("${ai.suggestion.queue.max-concurrent:3}")
    private int maxConcurrent;

    @Value("${ai.suggestion.queue.processing-interval:5000}")
    private long processingInterval;

    private final ExecutorService executorService = Executors.newFixedThreadPool(5);
    private final Semaphore processingSemaphore;
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    public AISuggestionQueueService(
            @Value("${ai.suggestion.queue.max-concurrent:3}") int maxConcurrent) {
        this.processingSemaphore = new Semaphore(maxConcurrent);
    }

    /**
     * Add AI suggestion request to queue
     */
    @Transactional
    public UUID addToQueue(AISuggestionRequest request) {
        try {
            // Check if queue is full
            long queuedCount = repository.countByStatus(AISuggestionStatus.QUEUED);
            if (queuedCount >= maxQueueSize) {
                throw new RuntimeException(
                        "AI Suggestion queue is full (" + maxQueueSize + " requests)");
            }

            // Get next queue position
            Integer nextPosition = repository.findNextQueuePosition(AISuggestionStatus.QUEUED);

            // Convert request to map for storage
            Map<String, Object> requestData = convertRequestToMap(request);

            // Create entity
            AISuggestionRequestEntity entity =
                    AISuggestionRequestEntity.builder().formId(request.getFormId())
                            .sampleCount(request.getSampleCount()).priority(request.getPriority())
                            .queuePosition(nextPosition).queuedAt(LocalDateTime.now())
                            .status(AISuggestionStatus.QUEUED).requestData(requestData).build();

            AISuggestionRequestEntity saved = repository.save(entity);

            log.info("Added AI suggestion request {} to queue at position {} with priority {}",
                    saved.getId(), nextPosition, request.getPriority());

            return saved.getId();

        } catch (Exception e) {
            log.error("Failed to add AI suggestion request to queue", e);
            throw new RuntimeException("Failed to queue AI suggestion request: " + e.getMessage());
        }
    }

    /**
     * Scheduled task to process queued AI suggestions with priority
     */
    @Scheduled(fixedRateString = "${ai.suggestion.queue.processing-interval:5000}")
    @Transactional
    public void processQueuedSuggestions() {
        try {
            // Check if we have capacity to process more requests
            if (activeRequests.get() >= maxConcurrent) {
                log.debug("No capacity available for processing AI suggestions (active: {})",
                        activeRequests.get());
                return;
            }

            // Get queued requests ordered by priority
            List<AISuggestionRequestEntity> queuedRequests = repository
                    .findByStatusOrderByPriorityDescCreatedAtAsc(AISuggestionStatus.QUEUED);

            if (queuedRequests.isEmpty()) {
                log.debug("No queued AI suggestions to process");
                return;
            }

            log.info("Found {} queued AI suggestions to process", queuedRequests.size());

            // Process requests up to available capacity
            int availableSlots = maxConcurrent - activeRequests.get();
            int processed = 0;

            for (AISuggestionRequestEntity request : queuedRequests) {
                if (processed >= availableSlots) {
                    break;
                }

                if (processAISuggestionRequest(request)) {
                    processed++;
                    log.info("Started processing AI suggestion request: {} ({}/{})",
                            request.getId(), processed, availableSlots);
                }
            }

            if (processed > 0) {
                log.info("Started processing {} AI suggestion requests", processed);
            }

        } catch (Exception e) {
            log.error("Error processing queued AI suggestions", e);
        }
    }

    /**
     * Get request status
     */
    public Map<String, Object> getRequestStatus(UUID requestId) {
        AISuggestionRequestEntity request = repository.findById(requestId).orElseThrow(
                () -> new RuntimeException("AI suggestion request not found: " + requestId));

        Map<String, Object> status = new HashMap<>();
        status.put("requestId", requestId);
        status.put("status", request.getStatus());
        status.put("priority", request.getPriority());
        status.put("queuePosition", request.getQueuePosition());
        status.put("createdAt", request.getCreatedAt());
        status.put("queuedAt", request.getQueuedAt());
        status.put("processingStartedAt", request.getProcessingStartedAt());
        status.put("processingCompletedAt", request.getProcessingCompletedAt());
        status.put("retryCount", request.getRetryCount());
        status.put("maxRetries", request.getMaxRetries());
        status.put("errorMessage", request.getErrorMessage());

        if (request.getStatus() == AISuggestionStatus.COMPLETED
                && request.getResultData() != null) {
            status.put("result", request.getResultData());
        }

        return status;
    }

    /**
     * Get queue statistics
     */
    public Map<String, Object> getQueueStatistics() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("queuedCount", repository.countByStatus(AISuggestionStatus.QUEUED));
        stats.put("processingCount", repository.countByStatus(AISuggestionStatus.PROCESSING));
        stats.put("completedCount", repository.countByStatus(AISuggestionStatus.COMPLETED));
        stats.put("failedCount", repository.countByStatus(AISuggestionStatus.FAILED));
        stats.put("activeRequests", activeRequests.get());
        stats.put("maxConcurrent", maxConcurrent);
        stats.put("maxQueueSize", maxQueueSize);
        stats.put("timestamp", LocalDateTime.now());

        return stats;
    }

    /**
     * Process a single AI suggestion request
     */
    private boolean processAISuggestionRequest(AISuggestionRequestEntity request) {
        try {
            // Update status to PROCESSING
            request.setStatus(AISuggestionStatus.PROCESSING);
            request.setProcessingStartedAt(LocalDateTime.now());
            repository.save(request);

            // Remove from queue and update positions
            if (request.getQueuePosition() != null) {
                repository.decrementQueuePositions(AISuggestionStatus.QUEUED,
                        request.getQueuePosition());
            }

            // Process asynchronously
            CompletableFuture.runAsync(() -> {
                try {
                    activeRequests.incrementAndGet();
                    processingSemaphore.acquire();

                    log.info("Processing AI suggestion request: {} (priority: {})", request.getId(),
                            request.getPriority());

                    // Convert back to request object
                    AISuggestionRequest aiRequest = convertMapToRequest(request.getRequestData());

                    // Convert AISuggestionRequest to AnswerAttributesRequest
                    AnswerAttributesRequest answerRequest =
                            convertToAnswerAttributesRequest(aiRequest);

                    // Generate answer attributes
                    AnswerAttributesResponse response =
                            aiSuggestionService.generateAnswerAttributes(answerRequest);

                    // Update with success
                    request.setStatus(AISuggestionStatus.COMPLETED);
                    request.setProcessingCompletedAt(LocalDateTime.now());
                    request.setResultData(convertResponseToMap(response));
                    repository.save(request);

                    log.info("Successfully completed AI suggestion request: {}", request.getId());

                } catch (Exception e) {
                    log.error("Failed to process AI suggestion request: {}", request.getId(), e);
                    handleProcessingError(request, e);
                } finally {
                    activeRequests.decrementAndGet();
                    processingSemaphore.release();
                }
            }, executorService);

            return true;

        } catch (Exception e) {
            log.error("Failed to start processing AI suggestion request: {}", request.getId(), e);
            handleProcessingError(request, e);
            return false;
        }
    }

    /**
     * Handle processing errors with retry logic
     */
    private void handleProcessingError(AISuggestionRequestEntity request, Exception error) {
        try {
            request.setRetryCount(request.getRetryCount() + 1);
            request.setErrorMessage(error.getMessage());

            if (request.getRetryCount() >= request.getMaxRetries()) {
                request.setStatus(AISuggestionStatus.FAILED);
                log.error("AI suggestion request {} exceeded max retries, marking as FAILED",
                        request.getId());
            } else {
                // Re-queue for retry
                request.setStatus(AISuggestionStatus.QUEUED);
                Integer nextPosition = repository.findNextQueuePosition(AISuggestionStatus.QUEUED);
                request.setQueuePosition(nextPosition);
                log.info("Re-queued AI suggestion request {} for retry (attempt {}/{})",
                        request.getId(), request.getRetryCount(), request.getMaxRetries());
            }

            repository.save(request);

        } catch (Exception e) {
            log.error("Failed to handle processing error for AI suggestion request: {}",
                    request.getId(), e);
        }
    }

    /**
     * Convert AISuggestionRequest to Map for storage
     */
    private Map<String, Object> convertRequestToMap(AISuggestionRequest request) {
        return objectMapper.convertValue(request, Map.class);
    }

    /**
     * Convert Map back to AISuggestionRequest
     */
    private AISuggestionRequest convertMapToRequest(Map<String, Object> requestData) {
        return objectMapper.convertValue(requestData, AISuggestionRequest.class);
    }

    /**
     * Convert AnswerAttributesResponse to Map for storage
     */
    private Map<String, Object> convertResponseToMap(AnswerAttributesResponse response) {
        return objectMapper.convertValue(response, Map.class);
    }

    /**
     * Convert AISuggestionRequest to AnswerAttributesRequest
     */
    private AnswerAttributesRequest convertToAnswerAttributesRequest(
            AISuggestionRequest aiRequest) {
        AnswerAttributesRequest.StatisticalRequirements statisticalRequirements =
                AnswerAttributesRequest.StatisticalRequirements.builder()
                        .includeRealisticBehavior(aiRequest.getIncludeRealisticBehavior())
                        .language(aiRequest.getLanguage()).build();

        AnswerAttributesRequest.Requirements requirements = AnswerAttributesRequest.Requirements
                .builder().statisticalRequirements(statisticalRequirements).build();

        return AnswerAttributesRequest.builder().formId(aiRequest.getFormId())
                .sampleCount(aiRequest.getSampleCount()).requirements(requirements).build();
    }
}
