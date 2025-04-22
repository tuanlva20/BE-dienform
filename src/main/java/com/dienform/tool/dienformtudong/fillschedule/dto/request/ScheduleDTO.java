package com.dienform.tool.dienformtudong.fillschedule.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleDTO {
    private Boolean isScheduled;
    private Boolean isDynamicByTime;
    private String timezone;
    
    @Min(value = 1, message = "Minimum interval must be at least 1")
    private Integer minInterval;
    
    @Min(value = 1, message = "Maximum interval must be at least 1")
    private Integer maxInterval;
    
    private String timeWindows;
    
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;
}