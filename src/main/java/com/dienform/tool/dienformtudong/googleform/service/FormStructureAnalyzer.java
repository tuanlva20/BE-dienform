package com.dienform.tool.dienformtudong.googleform.service;

import java.util.List;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.googleform.dto.FormStructure;
import com.dienform.tool.dienformtudong.googleform.dto.SectionInfo;

/**
 * Service for analyzing form structure from database
 */
public interface FormStructureAnalyzer {

  /**
   * Analyze form structure from saved data in database
   * 
   * @param form The form entity to analyze
   * @return FormStructure containing section information
   */
  FormStructure analyzeFormStructureFromDatabase(Form form);

  /**
   * Check if form has sections
   * 
   * @param form The form entity to check
   * @return true if form has sections, false otherwise
   */
  boolean hasSections(Form form);

  /**
   * Get section information from database
   * 
   * @param form The form entity
   * @return List of section information
   */
  List<SectionInfo> getSectionInfoFromDatabase(Form form);
}

