package com.dienform.tool.dienformtudong.googleform.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Google Form submission response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormSubmissionResponse {
    
    /**
     * ID of the form that was submitted to
     */
    private UUID formId;
    
    /**
     * ID of the fill request this submission was part of
     */
    private UUID fillRequestId;
    
    /**
     * Whether the submission was successful
     */
    private boolean success;
    
    /**
     * Timestamp when the submission was completed
     */
    private LocalDateTime submissionTime;
    
    /**
     * Error message if submission failed
     */
    private String errorMessage;
    
    /**
     * Response data from the form submission (typically confirmation or thank you page content)
     */
    private String responseData;
    
    /**
     * HTTP status code of the submission response
     */
    private int statusCode;
    
    /**
     * Any additional response metadata (e.g., confirmation numbers, submission IDs)
     */
    private Map<String, String> metadata;
}
