package com.dienform.tool.dienformtudong.surveyexecution.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.dienform.tool.dienformtudong.surveyexecution.dto.response.SurveyExecutionResponse;
import com.dienform.tool.dienformtudong.surveyexecution.service.ExecutionService;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SurveyExecutionController {

    private final ExecutionService executionService;

    @GetMapping("/form/{formId}/execution-history")
    public ResponseEntity<Page<SurveyExecutionResponse>> getExecutionHistory(
            @PathVariable UUID formId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "executionTime"));
        Page<SurveyExecutionResponse> history = executionService.getExecutionHistoryByFormId(formId, pageable);
        
        return ResponseEntity.ok(history);
    }

    @GetMapping("/fill-request/{requestId}/executions")
    public ResponseEntity<Page<SurveyExecutionResponse>> getExecutionsByRequestId(
            @PathVariable UUID requestId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "executionTime"));
        // Page<SurveyExecutionResponse> executions = executionService.getExecutionsByRequestId(requestId, pageable);
        
        return ResponseEntity.ok(null);
    }

    @GetMapping("/execution/{executionId}")
    public ResponseEntity<SurveyExecutionResponse> getExecutionById(@PathVariable UUID executionId) {
        // SurveyExecutionResponse execution = executionService.getExecutionById(executionId);
        return ResponseEntity.ok(null);
    }
}