package com.dienform.tool.dienformtudong.datamapping.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataMappingResponse {

  private List<Object> questions; // Changed to Object to avoid type conflicts
  private List<String> sheetColumns;
  private List<Object> autoMappings; // Changed to Object to avoid type conflicts
  private List<String> unmappedQuestions;
  private List<String> errors;
  private SheetAccessibilityInfo sheetAccessibilityInfo; // Added for accessibility information
}

