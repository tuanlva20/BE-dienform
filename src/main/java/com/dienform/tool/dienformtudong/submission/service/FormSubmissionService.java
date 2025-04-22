package com.dienform.tool.dienformtudong.submission.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;

public interface FormSubmissionService {
    /**
     * Submit a form with generated answers based on the requested distributions
     * @param requestId The ID of the fill request
     * @param formLink The public link to the Google Form
     * @param answerDistributions The distribution of answers to follow
     * @param isHumanLike Whether to simulate human-like submission behavior
     * @return Map containing the status and details of the submission
     */
    Map<String, Object> submitForm(UUID requestId, String formLink, List<AnswerDistribution> answerDistributions, boolean isHumanLike);
    
    /**
     * Submit multiple forms in batch based on the fill request
     * @param requestId The ID of the fill request
     * @return Map containing the status and details of the batch submission
     */
    Map<String, Object> submitFormBatch(UUID requestId);
}