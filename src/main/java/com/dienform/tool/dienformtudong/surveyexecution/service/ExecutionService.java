package com.dienform.tool.dienformtudong.surveyexecution.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.dienform.tool.dienformtudong.surveyexecution.dto.response.SurveyExecutionResponse;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SurveyExecution;

/**
 * Service interface for managing survey executions
 */
public interface ExecutionService {

    /**
     * Retrieve a page of execution history for a form
     *
     * @param formId The ID of the form
     * @param pageable Pagination information
     * @return Page of survey execution responses
     */
    Page<SurveyExecutionResponse> getExecutionHistoryByFormId(UUID formId, Pageable pageable);

    /**
     * Record a new survey execution
     *
     * @param execution The survey execution to save
     * @return The saved execution
     */
    SurveyExecution recordExecution(SurveyExecution execution);

    /**
     * Get executions for a fill request within a time range
     *
     * @param fillRequestId The ID of the fill request
     * @param startTime Start of time range
     * @param endTime End of time range
     * @return List of executions
     */
    List<SurveyExecution> getExecutionsByFillRequestIdAndTimeRange(UUID fillRequestId,
            LocalDateTime startTime, LocalDateTime endTime);
}
