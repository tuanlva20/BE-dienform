package com.dienform.tool.dienformtudong.datamapping.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataMappingRequest {
    
    @NotBlank(message = "Form ID is required")
    private String formId;

    @NotBlank(message = "Sheet link is required")
    @Pattern(regexp = "^https://docs\\.google\\.com/spreadsheets/d/[a-zA-Z0-9-_]+.*",
             message = "Invalid Google Sheets URL")
    private String sheetLink;
} 