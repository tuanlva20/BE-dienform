package com.dienform.tool.dienformtudong.datamapping.service;

import java.util.List;
import java.util.Map;
import com.dienform.tool.dienformtudong.datamapping.dto.response.SheetAccessibilityInfo;

/**
 * Service interface for Google Sheets operations Provides methods for reading, validating, and
 * checking accessibility of Google Sheets
 */
public interface GoogleSheetsService {

  /**
   * Validates Google Sheets URL and checks accessibility status This method should be called before
   * attempting to read data
   * 
   * @param sheetLink The Google Sheets URL to validate and check
   * @return SheetAccessibilityInfo containing validation and accessibility details
   */
  SheetAccessibilityInfo validateAndCheckAccessibility(String sheetLink);

  /**
   * Get column headers from Google Sheets Note: Call validateAndCheckAccessibility first to ensure
   * the sheet is accessible
   * 
   * @param sheetLink The Google Sheets URL
   * @return List of column headers from the first row
   * @throws Exception if sheet is not accessible or validation fails
   */
  List<String> getSheetColumns(String sheetLink) throws Exception;

  /**
   * Get all data from Google Sheets Note: Call validateAndCheckAccessibility first to ensure the
   * sheet is accessible
   * 
   * @param sheetLink The Google Sheets URL
   * @return List of maps representing sheet data (excluding header row)
   * @throws Exception if sheet is not accessible or validation fails
   */
  List<Map<String, Object>> getSheetData(String sheetLink) throws Exception;

  /**
   * Check if a Google Sheets URL is valid format
   * 
   * @param sheetLink The URL to validate
   * @return true if the URL is a valid Google Sheets URL format
   */
  boolean isValidGoogleSheetsUrl(String sheetLink);

  /**
   * Check if a sheet is publicly accessible (no API key required)
   * 
   * @param sheetLink The Google Sheets URL
   * @return true if the sheet can be accessed without authentication
   */
  boolean isSheetPublic(String sheetLink);
}
