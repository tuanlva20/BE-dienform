package com.dienform.tool.dienformtudong.form.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.dienform.tool.dienformtudong.form.dto.request.FormRequest;
import com.dienform.tool.dienformtudong.form.dto.response.FormDetailResponse;
import com.dienform.tool.dienformtudong.form.dto.response.FormResponse;
import java.util.UUID;

public interface FormService {
    Page<FormResponse> getAllForms(String search, Pageable pageable);
    FormDetailResponse getFormById(UUID id);
    FormResponse createForm(FormRequest formRequest);
    void deleteForm(UUID id);
}