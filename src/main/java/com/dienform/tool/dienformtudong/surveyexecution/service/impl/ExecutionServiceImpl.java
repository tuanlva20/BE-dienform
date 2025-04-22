package com.dienform.tool.dienformtudong.surveyexecution.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.surveyexecution.dto.response.SurveyExecutionResponse;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SurveyExecution;
import com.dienform.tool.dienformtudong.surveyexecution.mapper.SurveyExecutionMapper;
import com.dienform.tool.dienformtudong.surveyexecution.repository.SurveyExecutionRepository;
import com.dienform.tool.dienformtudong.surveyexecution.service.ExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of the ExecutionService interface for managing survey executions
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionServiceImpl implements ExecutionService {

    private final SurveyExecutionRepository executionRepository;
    private final SurveyExecutionMapper executionMapper;

    @Override
    public Page<SurveyExecutionResponse> getExecutionHistoryByFormId(UUID formId,
            Pageable pageable) {
        log.debug("Getting execution history for form ID: {}", formId);
        Page<SurveyExecution> executions = executionRepository.findByFormId(formId, pageable);
        return executions.map(executionMapper::toResponse);
    }

    @Override
    public SurveyExecution recordExecution(SurveyExecution execution) {
        log.debug("Recording new survey execution for fill request ID: {}",
                execution.getFillRequest().getId());
        return executionRepository.save(execution);
    }

    @Override
    public List<SurveyExecution> getExecutionsByFillRequestIdAndTimeRange(UUID fillRequestId,
            LocalDateTime startTime, LocalDateTime endTime) {
        log.debug("Getting executions for fill request ID: {} between {} and {}", fillRequestId,
                startTime, endTime);
        return executionRepository.findByFillRequestAndExecutionTimeBetween(fillRequestId,
                startTime, endTime);
    }
}
