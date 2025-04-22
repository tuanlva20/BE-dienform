package com.dienform.tool.dienformtudong.fillschedule.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleResponse {
    private UUID id;
    private UUID fillRequestId;
    private boolean isScheduled;
    private boolean isDynamicByTime;
    private String timezone;
    private Integer minInterval;
    private Integer maxInterval;
    private String timeWindows;
    private LocalDate startDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}