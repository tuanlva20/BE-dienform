package com.dienform.tool.dienformtudong.fillschedule.service;

import java.util.UUID;
import com.dienform.tool.dienformtudong.fillschedule.dto.request.ScheduleDTO;
import com.dienform.tool.dienformtudong.fillschedule.dto.response.ScheduleResponse;

public interface ScheduleService {
    /**
     * Create a new schedule for a fill request
     * @param requestId The ID of the fill request
     * @param scheduleDTO The schedule information
     * @return The created schedule
     */
    ScheduleResponse createSchedule(UUID requestId, ScheduleDTO scheduleDTO);
    
    /**
     * Get the schedule for a fill request
     * @param requestId The ID of the fill request
     * @return The schedule
     */
    ScheduleResponse getScheduleByRequestId(UUID requestId);
    
    /**
     * Update an existing schedule
     * @param requestId The ID of the fill request
     * @param scheduleDTO The updated schedule information
     * @return The updated schedule
     */
    ScheduleResponse updateSchedule(UUID requestId, ScheduleDTO scheduleDTO);
    
    /**
     * Delete a schedule
     * @param requestId The ID of the fill request
     */
    void deleteSchedule(UUID requestId);
}