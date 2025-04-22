package com.dienform.tool.dienformtudong.fillschedule.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.dienform.tool.dienformtudong.fillschedule.dto.request.ScheduleDTO;
import com.dienform.tool.dienformtudong.fillschedule.dto.response.ScheduleResponse;
import com.dienform.tool.dienformtudong.fillschedule.service.ScheduleService;
import java.util.UUID;

@RestController
@RequestMapping("/api/fill-request")
@RequiredArgsConstructor
public class FillScheduleController {

    private final ScheduleService scheduleService;

    @PostMapping("/{requestId}/schedule")
    public ResponseEntity<ScheduleResponse> createSchedule(
            @PathVariable UUID requestId,
            @Valid @RequestBody ScheduleDTO scheduleDTO) {
        ScheduleResponse response = scheduleService.createSchedule(requestId, scheduleDTO);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/{requestId}/schedule")
    public ResponseEntity<ScheduleResponse> getSchedule(@PathVariable UUID requestId) {
        ScheduleResponse response = scheduleService.getScheduleByRequestId(requestId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{requestId}/schedule")
    public ResponseEntity<ScheduleResponse> updateSchedule(
            @PathVariable UUID requestId,
            @Valid @RequestBody ScheduleDTO scheduleDTO) {
        ScheduleResponse response = scheduleService.updateSchedule(requestId, scheduleDTO);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{requestId}/schedule")
    public ResponseEntity<Void> deleteSchedule(@PathVariable UUID requestId) {
        scheduleService.deleteSchedule(requestId);
        return ResponseEntity.noContent().build();
    }
}