package com.dienform.tool.dienformtudong.aisuggestion.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.model.ResponseModel;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AISuggestionRequest;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AnswerAttributesRequest;
import com.dienform.tool.dienformtudong.aisuggestion.dto.response.AnswerAttributesResponse;
import com.dienform.tool.dienformtudong.aisuggestion.service.AISuggestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST Controller for AI Suggestion functionality Provides endpoints for answer attributes
 * generation and request validation
 */
@RestController
@RequestMapping("/api/ai-suggestion")
@RequiredArgsConstructor
@Slf4j
public class AISuggestionController {

  private final AISuggestionService aiSuggestionService;

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

  @PostMapping("/answer-attributes")
  public ResponseEntity<ResponseModel<AnswerAttributesResponse>> generateAnswerAttributes(
      @Valid @RequestBody AnswerAttributesRequest request) {

    log.info("Generating answer attributes for form: {}, samples: {}", request.getFormId(),
        request.getSampleCount());

    try {
      AnswerAttributesResponse response = aiSuggestionService.generateAnswerAttributes(request);
      return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));

    } catch (Exception e) {
      log.error("Error generating answer attributes: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(ResponseModel.error("Failed to generate answer attributes: " + e.getMessage(),
              HttpStatus.INTERNAL_SERVER_ERROR));
    }
  }
}
