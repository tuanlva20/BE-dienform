package com.dienform.tool.dienformtudong.form.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormRequest {

    @NotBlank(message = "Form name is required")
    private String name;
    
    @NotBlank(message = "Edit link is required")
    private String editLink;
}