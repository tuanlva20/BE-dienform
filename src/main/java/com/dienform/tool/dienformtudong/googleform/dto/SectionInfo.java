package com.dienform.tool.dienformtudong.googleform.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing information about a section in a form
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SectionInfo {
  private int sectionIndex;
  private String sectionTitle;
  private String sectionDescription;
  private List<QuestionInfo> questions;
  private boolean isLastSection;
}

