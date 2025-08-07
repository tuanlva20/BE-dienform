package com.dienform.tool.dienformtudong.googleform.service;

import java.util.List;
import java.util.Map;
import com.dienform.tool.dienformtudong.googleform.dto.GridQuestionAnswer;
import com.dienform.tool.dienformtudong.question.entity.Question;

/**
 * Service interface for handling grid questions
 */
public interface GridQuestionService {

  /**
   * Parse raw answer text into structured grid answer
   * 
   * @param question The question entity
   * @param rawAnswerText The raw answer text
   * @return Parsed grid answer
   */
  GridQuestionAnswer parseGridAnswer(Question question, String rawAnswerText);

  /**
   * Validate grid answer against question structure
   * 
   * @param question The question entity
   * @param gridAnswer The grid answer to validate
   * @return True if valid, false otherwise
   */
  boolean validateGridAnswer(Question question, GridQuestionAnswer gridAnswer);

  /**
   * Get available row labels for a grid question
   * 
   * @param question The question entity
   * @return List of row labels
   */
  List<String> getRowLabels(Question question);

  /**
   * Get available column options for a grid question
   * 
   * @param question The question entity
   * @return List of column options
   */
  List<String> getColumnOptions(Question question);

  /**
   * Convert grid answer to map format for easy access
   * 
   * @param gridAnswer The grid answer
   * @return Map of row labels to selected options
   */
  Map<String, Object> convertToRowMap(GridQuestionAnswer gridAnswer);

  /**
   * Convert grid answer to list format
   * 
   * @param gridAnswer The grid answer
   * @return List of row answers
   */
  List<GridQuestionAnswer.RowAnswer> convertToRowList(GridQuestionAnswer gridAnswer);
}
