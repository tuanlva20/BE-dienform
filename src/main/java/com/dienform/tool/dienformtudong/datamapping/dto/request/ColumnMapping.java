package com.dienform.tool.dienformtudong.datamapping.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnMapping {
  @NotBlank(message = "Question ID is required")
  private String questionId;
  private String columnName;
}