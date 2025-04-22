package com.dienform.tool.dienformtudong.googleform.service;

import java.util.List;
import com.dienform.tool.dienformtudong.form.dto.request.FormRequest;
import com.dienform.tool.dienformtudong.form.dto.response.FormResponse;
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
    FormResponse parseForm(FormRequest request);

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
    List<ExtractedQuestion> getFormQuestions(String formUrl);
}
