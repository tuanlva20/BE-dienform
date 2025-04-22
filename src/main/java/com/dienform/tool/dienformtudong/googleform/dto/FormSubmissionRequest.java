package com.dienform.tool.dienformtudong.googleform.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for requesting a Google Form submission
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormSubmissionRequest {
    
    /**
     * ID of the form to submit to
     */
    private UUID formId;
    
    /**
     * Edit link/URL of the Google Form
     */
    private String formUrl;
    
    /**
     * ID of the fill request this submission is part of
     */
    private UUID fillRequestId;
    
    /**
     * Whether to make the submission look human-like (with varying timing)
     */
    private boolean humanLike;
    
    /**
     * Map of question IDs to selected answer/option IDs
     * For multiple-choice questions, this will contain a single value
     * For checkbox questions, this will contain multiple values in a list
     */
    private Map<String, List<String>> answers;
    
    /**
     * Additional data that might be required for the form submission
     * (e.g., form specific IDs, tokens, etc.)
     */
    private Map<String, String> metadata;
}
