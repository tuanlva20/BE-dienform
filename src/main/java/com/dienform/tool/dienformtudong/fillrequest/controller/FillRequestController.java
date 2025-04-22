package com.dienform.tool.dienformtudong.fillrequest.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.dienform.tool.dienformtudong.fillrequest.dto.request.FillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;
import com.dienform.tool.dienformtudong.fillrequest.service.FillRequestService;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FillRequestController {

    private final FillRequestService fillRequestService;

    @PostMapping("/form/{formId}/fill-request")
    public ResponseEntity<FillRequestResponse> createFillRequest(
            @PathVariable UUID formId,
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

    @DeleteMapping("/fill-request/{requestId}")
    public ResponseEntity<Void> deleteFillRequest(@PathVariable UUID requestId) {
        fillRequestService.deleteFillRequest(requestId);
        return ResponseEntity.noContent().build();
    }
}