package com.dienform.tool.dienformtudong.form.controller;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.model.ResponseModel;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.tool.dienformtudong.form.dto.param.FormParam;
import com.dienform.tool.dienformtudong.form.dto.request.FormRequest;
import com.dienform.tool.dienformtudong.form.dto.response.FormDetailResponse;
import com.dienform.tool.dienformtudong.form.dto.response.FormResponse;
import com.dienform.tool.dienformtudong.form.service.FormService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/form")
@RequiredArgsConstructor
public class FormController {

    private final FormService formService;
    private final CurrentUserUtil currentUserUtil;

    @Qualifier("businessAsyncExecutor")
    private final java.util.concurrent.Executor businessAsyncExecutor;

    @GetMapping
    public ResponseModel<?> getAllForms(@RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String createdBy = currentUserUtil.getCurrentUserIdIfPresent().map(java.util.UUID::toString)
                .orElse(null);
        FormParam param = FormParam.builder().search(search).createdBy(createdBy).page(page)
                .size(size).build();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<FormResponse> forms = formService.getAllForms(param, pageable);
        return ResponseModel.success(forms.getContent(), forms.getTotalPages(), page, size,
                forms.getTotalElements(), HttpStatus.OK);
    }

    @GetMapping("/{formId}")
    public ResponseEntity<FormDetailResponse> getFormById(@PathVariable UUID formId) {
        FormDetailResponse form = formService.getFormById(formId);
        return ResponseEntity.ok(form);
    }

    @GetMapping("/user/all")
    public ResponseModel<?> getAllFormsByCurrentUser() {
        UUID userId = currentUserUtil.getCurrentUserIdIfPresent()
                .orElseThrow(() -> new RuntimeException("User not authenticated"));

        java.util.List<FormResponse> forms = formService.getAllFormsByUserId(userId);
        return ResponseModel.success(forms, HttpStatus.OK);
    }

    @PostMapping
    public ResponseEntity<FormResponse> createForm(@Valid @RequestBody FormRequest formRequest) {
        // Return only when form is fully created (success/failure handled inside service)
        FormResponse createdForm = formService.createForm(formRequest);
        return new ResponseEntity<>(createdForm, HttpStatus.CREATED);
    }

    @PutMapping("/{formId}")
    public ResponseEntity<FormResponse> updateForm(@PathVariable UUID formId,
            @Valid @RequestBody FormRequest formRequest) {
        FormResponse updatedForm = formService.updateForm(formId, formRequest);
        return ResponseEntity.ok(updatedForm);
    }

    @DeleteMapping("/{formId}")
    public ResponseEntity<Void> deleteForm(@PathVariable UUID formId) {
        formService.deleteForm(formId);
        return ResponseEntity.noContent().build();
    }
}
