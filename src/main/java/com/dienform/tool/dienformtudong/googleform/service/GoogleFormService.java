package com.dienform.tool.dienformtudong.googleform.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.dienform.tool.dienformtudong.googleform.dto.FormSubmissionRequest;
import com.dienform.tool.dienformtudong.googleform.dto.FormSubmissionResponse;
import com.dienform.tool.dienformtudong.googleform.util.GoogleFormParser.ExtractedQuestion;

/**
 * Service for interacting with Google Forms
 */
public interface GoogleFormService {
    FormSubmissionResponse submitForm(FormSubmissionRequest request);

    /**
     * Get the questions from a Google Form
     * 
     * @param formUrl The URL of the Google Form
     * @return List of extracted questions
     */
    List<ExtractedQuestion> readGoogleForm(String formUrl);

    /**
     * Extract the title from the edit link
     * 
     * @param editLink The edit link of the Google Form
     * @return The title of the Google Form
     */
    String extractTitleFromFormLink(String editLink);

    /**
     * Fill Google Form using Selenium with distributed answers based on requested percentages
     * 
     * @param fillRequestId The ID of the fill request
     * @return Number of successful form submissions
     */
    int fillForm(UUID fillRequestId);

    /**
     * Submit form data using browser automation
     * 
     * @param formUrl The URL of the form to submit
     * @param formData Map of question IDs to answers
     * @return true if submission was successful, false otherwise
     */
    boolean submitFormWithBrowser(UUID fillRequestId, UUID formId, String formUrl,
            Map<String, String> formData);

    /**
     * Clear all caches to free memory and avoid stale data
     */
    void clearCaches();

    /**
     * Expose form questions cache for operational endpoints
     */
    Map<String, List<ExtractedQuestion>> getFormQuestionsCache();

    /**
     * Reset fill request status to PENDING if it's stuck in RUNNING state
     * 
     * @param fillRequestId The ID of the fill request to reset
     */
    void resetFillRequestStatus(UUID fillRequestId);
}
