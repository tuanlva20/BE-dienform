package com.dienform.tool.dienformtudong.datamapping.util;

import java.util.ArrayList;
import java.util.List;
import com.dienform.tool.dienformtudong.question.dto.response.QuestionResponse;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for data mapping operations Provides methods for formatting columns and cleaning
 * question titles
 */
@UtilityClass
@Slf4j
public class DataMappingUtil {

  /**
   * Format sheet columns with Excel-style prefixes (A, B, C, etc.)
   * 
   * @param columnNames List of raw column names from the sheet
   * @return List of formatted column names with prefixes
   */
  public List<String> formatSheetColumns(List<String> columnNames) {
    List<String> formattedColumns = new ArrayList<>();

    for (int i = 0; i < columnNames.size(); i++) {
      String columnLetter = getColumnLetter(i);
      String columnName = columnNames.get(i);

      // Format: "A - Column Name"
      String formattedColumn = columnLetter + " - " + columnName;
      formattedColumns.add(formattedColumn);

      log.debug("Formatted column {}: '{}' -> '{}'", i, columnName, formattedColumn);
    }

    return formattedColumns;
  }

  /**
   * Clean question titles by removing asterisk (*) for required questions and any other formatting
   * characters
   * 
   * @param questions List of questions to clean
   * @return List of questions with cleaned titles
   */
  public List<QuestionResponse> cleanQuestionTitles(List<QuestionResponse> questions) {
    List<QuestionResponse> cleanedQuestions = new ArrayList<>();

    for (QuestionResponse question : questions) {
      String originalTitle = question.getTitle();
      String cleanedTitle = cleanTitle(originalTitle);

      // Create new QuestionResponse with cleaned title
      QuestionResponse cleanedQuestion = QuestionResponse.builder().id(question.getId())
          .title(cleanedTitle).description(question.getDescription()).type(question.getType())
          .required(question.isRequired()).position(question.getPosition())
          .options(question.getOptions()).build();

      cleanedQuestions.add(cleanedQuestion);

      if (!originalTitle.equals(cleanedTitle)) {
        log.debug("Cleaned question title: '{}' -> '{}'", originalTitle, cleanedTitle);
      }
    }

    return cleanedQuestions;
  }

  /**
   * Clean a single title by removing formatting characters
   * 
   * @param title Original title
   * @return Cleaned title
   */
  public String cleanTitle(String title) {
    if (title == null || title.trim().isEmpty()) {
      return title;
    }

    String cleaned = title.trim();

    // Remove asterisk (*) that indicates required fields
    cleaned = cleaned.replaceAll("\\*+$", "").trim();

    // Remove other common formatting characters at the end
    cleaned = cleaned.replaceAll("[*+!?]+$", "").trim();

    // Remove multiple spaces
    cleaned = cleaned.replaceAll("\\s+", " ").trim();

    return cleaned;
  }

  /**
   * Extract the original column name from a formatted column string
   * 
   * @param formattedColumn Formatted column like "A - Column Name"
   * @return Original column name
   */
  public String extractOriginalColumnName(String formattedColumn) {
    if (formattedColumn == null || !formattedColumn.contains(" - ")) {
      return formattedColumn;
    }

    String[] parts = formattedColumn.split(" - ", 2);
    return parts.length > 1 ? parts[1] : formattedColumn;
  }

  /**
   * Validate if a column reference is valid Excel-style
   * 
   * @param columnLetter Column letter(s) to validate
   * @return true if valid Excel column reference
   */
  public boolean isValidColumnLetter(String columnLetter) {
    if (columnLetter == null || columnLetter.trim().isEmpty()) {
      return false;
    }

    return columnLetter.matches("^[A-Z]+$");
  }

  /**
   * Get column index from Excel-style column letter
   * 
   * @param columnLetter Column letter(s) like "A", "B", "AA"
   * @return 0-based column index
   */
  public int getColumnIndex(String columnLetter) {
    if (!isValidColumnLetter(columnLetter)) {
      return -1;
    }

    int result = 0;
    for (int i = 0; i < columnLetter.length(); i++) {
      result = result * 26 + (columnLetter.charAt(i) - 'A' + 1);
    }

    return result - 1;
  }

  /**
   * Check if a title appears to be a required field based on formatting
   * 
   * @param title Question title to check
   * @return true if title suggests it's a required field
   */
  public boolean appearsToBRequired(String title) {
    if (title == null) {
      return false;
    }

    return title.trim().endsWith("*") || title.trim().endsWith("**") || title.contains("(required)")
        || title.contains("(bắt buộc)");
  }

  /**
   * Get Excel-style column letter(s) for a given index A, B, C, ..., Z, AA, AB, AC, ...
   * 
   * @param index Column index (0-based)
   * @return Column letter(s)
   */
  private String getColumnLetter(int index) {
    StringBuilder result = new StringBuilder();

    while (index >= 0) {
      result.insert(0, (char) ('A' + (index % 26)));
      index = index / 26 - 1;
    }

    return result.toString();
  }
}
