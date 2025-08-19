package com.dienform.tool.dienformtudong.googleform.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing the structure of a Google Form
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormStructure {
  private FormStructureType type;
  private List<SectionInfo> sections;
  private int totalSections;
  private boolean hasNextButton;
  private boolean hasSubmitButton;
}
