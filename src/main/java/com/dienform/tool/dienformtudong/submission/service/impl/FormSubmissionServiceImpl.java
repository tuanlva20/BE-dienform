package com.dienform.tool.dienformtudong.submission.service.impl;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.util.Constants;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.answerdistribution.repository.AnswerDistributionRepository;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.question.repository.QuestionOptionRepository;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import com.dienform.tool.dienformtudong.submission.service.FormSubmissionService;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SurveyExecution;
import com.dienform.tool.dienformtudong.surveyexecution.repository.SurveyExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FormSubmissionServiceImpl implements FormSubmissionService {

    private final SurveyExecutionRepository executionRepository;

    @Override
    @Transactional
    public Map<String, Object> submitForm(UUID requestId, String formLink,
            List<AnswerDistribution> answerDistributions, boolean isHumanLike) {
        log.info("Submitting single form for request ID: {}", requestId);

        // Create execution record
        SurveyExecution execution = SurveyExecution.builder()
//            .fillRequestId(requestId)
                .executionTime(LocalDateTime.now()).status(Constants.EXECUTION_STATUS_PENDING)
                .build();

        SurveyExecution savedExecution = executionRepository.save(execution);

        try {
            // In a real implementation, this would use Selenium or similar to actually fill out and
            // submit the form
            // Here we'll just simulate the process

            // Simulate form submission time based on complexity and human-like behavior
            simulateSubmissionTime(answerDistributions.size(), isHumanLike);

            // Update execution record to completed
            savedExecution.setStatus(Constants.EXECUTION_STATUS_COMPLETED);
            executionRepository.save(savedExecution);

            // Return success response
            Map<String, Object> response = new HashMap<>();
            response.put("executionId", savedExecution.getId());
            response.put("status", "success");
            response.put("message", "Form submitted successfully");
            response.put("submissionTime", LocalDateTime.now());

            return response;
        } catch (Exception e) {
            log.error("Error submitting form for request ID: {}", requestId, e);

            // Update execution record to failed
            savedExecution.setStatus(Constants.EXECUTION_STATUS_FAILED);
            savedExecution.setErrorMessage(e.getMessage());
            executionRepository.save(savedExecution);

            // Return error response
            Map<String, Object> response = new HashMap<>();
            response.put("executionId", savedExecution.getId());
            response.put("status", "error");
            response.put("message", "Form submission failed: " + e.getMessage());

            return response;
        }
    }

    @Override
    @Transactional
    public Map<String, Object> submitFormBatch(UUID requestId) {
        return null;
        // log.info("Starting batch form submission for request ID: {}", requestId);

        // // Get fill request
        // FillRequest fillRequest = fillRequestRepository.findById(requestId)
        // .orElseThrow(() -> new ResourceNotFoundException("Fill Request", "id", requestId));

        // // Get form
        // Form form = formRepository.findById(fillRequest.getFormId())
        // .orElseThrow(() -> new ResourceNotFoundException("Form", "id", fillRequest.getFormId()));

        // if (form.getPublicLink() == null || form.getPublicLink().isEmpty()) {
        // throw new BadRequestException("Form public link is missing");
        // }

        // // Get answer distributions
        // List<AnswerDistribution> distributions =
        // distributionRepository.findByFillRequestId(requestId);

        // if (distributions.isEmpty()) {
        // throw new BadRequestException("No answer distributions found for this fill request");
        // }

        // // Update fill request status
        // fillRequest.setStatus(Constants.FILL_REQUEST_STATUS_RUNNING);
        // fillRequestRepository.save(fillRequest);

        // // Start asynchronous batch processing
        // processBatchAsync(requestId, form.getPublicLink(), distributions,
        // fillRequest.getSurveyCount(), fillRequest.isHumanLike());

        // // Return immediate response
        // Map<String, Object> response = new HashMap<>();
        // response.put("requestId", requestId);
        // response.put("status", "processing");
        // response.put("message", "Batch submission started");
        // response.put("totalSurveys", fillRequest.getSurveyCount());
        // response.put("startTime", LocalDateTime.now());

        // return response;
        // }

        // @Async
        // protected void processBatchAsync(UUID requestId, String formLink,
        // List<AnswerDistribution> allDistributions, int totalCount, boolean isHumanLike) {
        // log.info("Processing async batch for request ID: {} with {} submissions", requestId,
        // totalCount);

        // try {
        // // Group distributions by question
        // Map<UUID, List<AnswerDistribution>> distributionsByQuestion = allDistributions.stream()
        // .collect(Collectors.groupingBy(AnswerDistribution::getQuestionId));

        // // Process each form submission
        // List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();

        // for (int i = 0; i < totalCount; i++) {
        // // For each submission, select options based on the distribution
        // List<AnswerDistribution> selectedDistributions = new ArrayList<>();

        // for (Map.Entry<UUID, List<AnswerDistribution>> entry :
        // distributionsByQuestion.entrySet()) {
        // UUID questionId = entry.getKey();
        // List<AnswerDistribution> questionDistributions = entry.getValue();

        // // Select an option based on the distribution percentages
        // AnswerDistribution selected = selectOptionBasedOnDistribution(questionDistributions);
        // if (selected != null) {
        // selectedDistributions.add(selected);
        // }
        // }

        // // Submit the form with the selected answers
        // CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() ->
        // submitForm(requestId, formLink, selectedDistributions, isHumanLike)
        // );

        // futures.add(future);

        // // Add a small delay between submissions to avoid overwhelming the server
        // if (isHumanLike) {
        // try {
        // Thread.sleep(ThreadLocalRandom.current().nextInt(2000, 15000));
        // } catch (InterruptedException e) {
        // Thread.currentThread().interrupt();
        // }
        // }
        // }

        // // Wait for all submissions to complete
        // CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // // Count successful and failed submissions
        // long successfulCount = futures.stream()
        // .map(CompletableFuture::join)
        // .filter(result -> "success".equals(result.get("status")))
        // .count();

        // // Update fill request status
        // FillRequest fillRequest = fillRequestRepository.findById(requestId).orElse(null);
        // if (fillRequest != null) {
        // fillRequest.setStatus(Constants.FILL_REQUEST_STATUS_COMPLETED);
        // fillRequestRepository.save(fillRequest);
        // }

        // log.info("Batch processing completed for request ID: {}. Successful submissions: {}/{}",
        // requestId, successfulCount, totalCount);
        // } catch (Exception e) {
        // log.error("Error in batch processing for request ID: {}", requestId, e);

        // // Update fill request status to failed
        // FillRequest fillRequest = fillRequestRepository.findById(requestId).orElse(null);
        // if (fillRequest != null) {
        // fillRequest.setStatus(Constants.FILL_REQUEST_STATUS_FAILED);
        // fillRequestRepository.save(fillRequest);
        // }
        // }
    }

    private AnswerDistribution selectOptionBasedOnDistribution(
            List<AnswerDistribution> distributions) {
        if (distributions == null || distributions.isEmpty()) {
            return null;
        }

        int totalPercentage =
                distributions.stream().mapToInt(AnswerDistribution::getPercentage).sum();

        if (totalPercentage <= 0) {
            return null;
        }

        // Generate a random number between 0 and the total percentage
        int randomValue = ThreadLocalRandom.current().nextInt(totalPercentage);

        // Select option based on the random value and distribution percentages
        int cumulativePercentage = 0;

        for (AnswerDistribution distribution : distributions) {
            cumulativePercentage += distribution.getPercentage();

            if (randomValue < cumulativePercentage) {
                return distribution;
            }
        }

        // Fallback to the last option
        return distributions.get(distributions.size() - 1);
    }

    private void simulateSubmissionTime(int questionCount, boolean isHumanLike) {
        try {
            // Base time in milliseconds
            int baseTime = 1000;

            if (isHumanLike) {
                // Human-like filling takes more time with randomness
                int totalTime =
                        baseTime + questionCount * ThreadLocalRandom.current().nextInt(1000, 5000);

                // Add some randomness to simulate thinking time
                totalTime += ThreadLocalRandom.current().nextInt(2000, 10000);

                Thread.sleep(totalTime);
            } else {
                // Fast automated filling
                int totalTime =
                        baseTime + questionCount * ThreadLocalRandom.current().nextInt(100, 500);
                Thread.sleep(totalTime);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
