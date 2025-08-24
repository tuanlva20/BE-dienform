package com.dienform.tool.dienformtudong.aisuggestion.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.model.ResponseModel;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AISuggestionRequest;
import com.dienform.tool.dienformtudong.aisuggestion.service.AISuggestionQueueService;
import com.dienform.tool.dienformtudong.aisuggestion.service.AISuggestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST Controller for AI Suggestion functionality Provides endpoints for answer attributes
 * generation and request validation Now supports priority-based queue processing
 */
@RestController
@RequestMapping("/api/ai-suggestion")
@RequiredArgsConstructor
@Slf4j
public class AISuggestionController {

  private final AISuggestionService aiSuggestionService;
  private final AISuggestionQueueService aiSuggestionQueueService;

  @PostMapping("/validate")
  public ResponseEntity<ResponseModel<Map<String, Object>>> validateRequest(
      @Valid @RequestBody AISuggestionRequest request) {

    log.debug("Validating AI request for form: {}", request.getFormId());

    try {
      Map<String, Object> validation = aiSuggestionService.validateRequest(request);
      return ResponseEntity.ok(ResponseModel.success(validation, HttpStatus.OK));

    } catch (Exception e) {
      log.error("Error validating request: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
          ResponseModel.error("Validation failed: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Queue-based AI suggestion processing endpoint This endpoint adds requests to a priority queue
   * for background processing Frontend should poll the status endpoint to get results
   */
  @PostMapping("/answer-attributes")
  public ResponseEntity<ResponseModel<Map<String, Object>>> generateAnswerAttributes(
      @Valid @RequestBody AISuggestionRequest request) {

    log.info("Queueing AI suggestion request for form: {}, samples: {}, priority: {}",
        request.getFormId(), request.getSampleCount(), request.getPriority());

    try {
      // Add request to queue for background processing
      UUID requestId = aiSuggestionQueueService.addToQueue(request);

      Map<String, Object> response = new java.util.HashMap<>();
      response.put("requestId", requestId);
      response.put("status", "QUEUED");
      response.put("message", "AI suggestion request added to queue");
      response.put("priority", request.getPriority());
      response.put("estimatedWaitTime", calculateEstimatedWaitTime(request.getPriority()));

      return ResponseEntity.accepted().body(ResponseModel.success(response, HttpStatus.ACCEPTED));

    } catch (Exception e) {
      log.error("Error queueing AI suggestion request: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResponseModel
          .error("Failed to queue request: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR));
    }
  }

  /**
   * Get request status and results Frontend should poll this endpoint to check if processing is
   * complete
   */
  @GetMapping("/status/{requestId}")
  public ResponseEntity<ResponseModel<Map<String, Object>>> getRequestStatus(
      @PathVariable UUID requestId) {

    log.debug("Getting status for AI suggestion request: {}", requestId);

    try {
      Map<String, Object> status = aiSuggestionQueueService.getRequestStatus(requestId);
      return ResponseEntity.ok(ResponseModel.success(status, HttpStatus.OK));

    } catch (Exception e) {
      log.error("Error getting request status: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(ResponseModel.error("Request not found: " + e.getMessage(), HttpStatus.NOT_FOUND));
    }
  }

  /**
   * Get queue statistics for monitoring
   */
  @GetMapping("/queue/statistics")
  public ResponseEntity<ResponseModel<Map<String, Object>>> getQueueStatistics() {

    log.debug("Getting AI suggestion queue statistics");

    try {
      Map<String, Object> stats = aiSuggestionQueueService.getQueueStatistics();
      return ResponseEntity.ok(ResponseModel.success(stats, HttpStatus.OK));

    } catch (Exception e) {
      log.error("Error getting queue statistics: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResponseModel.error(
          "Failed to get queue statistics: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR));
    }
  }

  /**
   * Calculate estimated wait time based on priority
   */
  private int calculateEstimatedWaitTime(int priority) {
    // Base wait time per priority level (in minutes)
    return switch (priority) {
      case 10 -> 1; // Highest priority: 1 minute
      case 9 -> 2; // Very high: 2 minutes
      case 8 -> 5; // High: 5 minutes
      case 7 -> 10; // Medium high: 10 minutes
      case 6 -> 15; // Medium: 15 minutes
      case 5 -> 30; // Default: 30 minutes
      case 4 -> 45; // Medium low: 45 minutes
      case 3 -> 60; // Low: 60 minutes
      case 2 -> 90; // Very low: 90 minutes
      case 1 -> 120; // Lowest: 120 minutes
      default -> 30; // Default: 30 minutes
    };
  }
}
