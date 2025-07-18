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

    FormResponse updateForm(UUID formId, FormRequest formRequest);

    void deleteForm(UUID formId);

    Form findByIdWithFetch(UUID id);
}
