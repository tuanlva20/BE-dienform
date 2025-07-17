package com.dienform.tool.dienformtudong.googleform.service;

import java.util.List;
import java.util.UUID;
import com.dienform.tool.dienformtudong.googleform.dto.FormSubmissionRequest;
import com.dienform.tool.dienformtudong.googleform.dto.FormSubmissionResponse;
import com.dienform.tool.dienformtudong.googleform.util.GoogleFormParser.ExtractedQuestion;

/**
 * Service for interacting with Google Forms
 */
public interface GoogleFormService {

    /**
     * Parse a Google Form and extract its questions
     * 
     * @param request The form request containing the form URL
     * @return Form response with extracted questions
     */
    // FormResponse parseForm(FormRequest request);

    /**
     * Submit answers to a Google Form
     * 
     * @param request The form submission request
     * @return Form submission response
     */
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
}
