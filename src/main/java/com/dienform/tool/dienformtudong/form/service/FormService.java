package com.dienform.tool.dienformtudong.form.service;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.dienform.tool.dienformtudong.form.dto.param.FormParam;
import com.dienform.tool.dienformtudong.form.dto.request.FormRequest;
import com.dienform.tool.dienformtudong.form.dto.response.FormDetailResponse;
import com.dienform.tool.dienformtudong.form.dto.response.FormResponse;
import com.dienform.tool.dienformtudong.form.entity.Form;

public interface FormService {
    Page<FormResponse> getAllForms(FormParam param, Pageable pageable);

    FormDetailResponse getFormById(UUID formId);

    FormResponse createForm(FormRequest formRequest);

    /**
     * Create initial form (fast operation - just basic validation and persistence)
     */
    FormResponse createFormInitial(FormRequest formRequest);

    /**
     * Process form asynchronously (heavy operations - parsing, encoding, etc.)
     */
    void processFormAsync(UUID formId, FormRequest formRequest);

    /**
     * Mark form as failed after async processing error
     */
    void markFormAsFailed(UUID formId, String errorMessage);

    FormResponse updateForm(UUID formId, FormRequest formRequest);

    void deleteForm(UUID formId);

    Form findByIdWithFetch(UUID id);

    /**
     * Get all forms for a specific user without pagination (for combobox)
     * 
     * @param userId The user ID to filter forms by
     * @return List of form responses
     */
    java.util.List<FormResponse> getAllFormsByUserId(UUID userId);
}
