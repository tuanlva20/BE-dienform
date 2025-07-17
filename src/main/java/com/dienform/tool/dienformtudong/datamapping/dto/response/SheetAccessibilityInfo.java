package com.dienform.tool.dienformtudong.datamapping.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO containing information about Google Sheets accessibility status Used to determine if a sheet
 * is public, private, or inaccessible
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SheetAccessibilityInfo {

  /**
   * Whether the sheet can be accessed by our system
   */
  private boolean isAccessible;

  /**
   * Whether the sheet is publicly accessible (no API key required)
   */
  private boolean isPublic;

  /**
   * The access method that should be used to read the sheet Possible values: "OPENSHEET_API",
   * "GOOGLE_SHEETS_API", null
   */
  private String accessMethod;

  /**
   * Human-readable message describing the accessibility status
   */
  private String message;

  /**
   * Additional details about cost implications (optional)
   */
  private String costImplication;

  /**
   * Recommendation for user action (optional)
   */
  private String userRecommendation;
}
