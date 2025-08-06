package com.dienform.tool.dienformtudong.fillrequest.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.request.FillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;
import com.dienform.tool.dienformtudong.fillrequest.service.FillRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FillRequestController {

    private final FillRequestService fillRequestService;

    @PostMapping("/form/{formId}/fill-request")
    public ResponseEntity<FillRequestResponse> createFillRequest(@PathVariable UUID formId,
            @Valid @RequestBody FillRequestDTO fillRequestDTO) {
        FillRequestResponse response = fillRequestService.createFillRequest(formId, fillRequestDTO);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/fill-request/{requestId}")
    public ResponseEntity<FillRequestResponse> getFillRequest(@PathVariable UUID requestId) {
        FillRequestResponse response = fillRequestService.getFillRequestById(requestId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/fill-request/{requestId}/start")
    public ResponseEntity<Map<String, Object>> startFillRequest(@PathVariable UUID requestId) {
        Map<String, Object> response = fillRequestService.startFillRequest(requestId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/fill-request/{requestId}/reset")
    public ResponseEntity<Map<String, Object>> resetFillRequest(@PathVariable UUID requestId) {
        Map<String, Object> response = fillRequestService.resetFillRequest(requestId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/fill-request/clear-caches")
    public ResponseEntity<Map<String, Object>> clearCaches() {
        Map<String, Object> response = fillRequestService.clearCaches();
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/fill-request/{requestId}")
    public ResponseEntity<Void> deleteFillRequest(@PathVariable UUID requestId) {
        fillRequestService.deleteFillRequest(requestId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/fill-request/fill-in-data")
    public ResponseEntity<FillRequestResponse> createDataFillRequest(
            @Valid @RequestBody DataFillRequestDTO dataFillRequestDTO) {
        FillRequestResponse response = fillRequestService.createDataFillRequest(dataFillRequestDTO);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
}
