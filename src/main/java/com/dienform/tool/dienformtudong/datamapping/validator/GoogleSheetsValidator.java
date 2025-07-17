package com.dienform.tool.dienformtudong.datamapping.validator;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import com.dienform.tool.dienformtudong.datamapping.dto.response.SheetAccessibilityInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Comprehensive validator for Google Sheets URLs and accessibility Handles validation,
 * accessibility checking, and provides detailed feedback
 */
@Component
@Slf4j
public class GoogleSheetsValidator {

  /**
   * Result of basic URL validation
   */
  public static class ValidationResult {
    public static ValidationResult valid(String message, String spreadsheetId) {
      return new ValidationResult(true, message, spreadsheetId);
    }

    public static ValidationResult invalid(String message) {
      return new ValidationResult(false, message, null);
    }

    private final boolean valid;

    private final String message;

    private final String spreadsheetId;

    private ValidationResult(boolean valid, String message, String spreadsheetId) {
      this.valid = valid;
      this.message = message;
      this.spreadsheetId = spreadsheetId;
    }

    public boolean isValid() {
      return valid;
    }

    public String getMessage() {
      return message;
    }

    public String getSpreadsheetId() {
      return spreadsheetId;
    }
  }
  /**
   * Result of accessibility checking
   */
  private static class AccessibilityCheckResult {
    public static AccessibilityCheckResult accessible(String message) {
      return new AccessibilityCheckResult(true, message);
    }

    public static AccessibilityCheckResult notAccessible(String message) {
      return new AccessibilityCheckResult(false, message);
    }

    private final boolean accessible;

    private final String message;

    private AccessibilityCheckResult(boolean accessible, String message) {
      this.accessible = accessible;
      this.message = message;
    }

    public boolean isAccessible() {
      return accessible;
    }

    public String getMessage() {
      return message;
    }
  }

  // Google Sheets URL patterns
  private static final String GOOGLE_SHEETS_DOMAIN_PATTERN =
      "^https://docs\\.google\\.com/spreadsheets/";

  private static final String SPREADSHEET_ID_PATTERN = "/spreadsheets/d/([a-zA-Z0-9-_]+)";
  private static final String VALID_SPREADSHEET_ID_PATTERN = "^[a-zA-Z0-9-_]+$";
  // Compiled patterns for performance
  private static final Pattern DOMAIN_REGEX = Pattern.compile(GOOGLE_SHEETS_DOMAIN_PATTERN);

  private static final Pattern ID_REGEX = Pattern.compile(SPREADSHEET_ID_PATTERN);

  private static final Pattern VALID_ID_REGEX = Pattern.compile(VALID_SPREADSHEET_ID_PATTERN);

  @Value("${google.api.key:}")
  private String googleApiKey;

  @Autowired
  private RestTemplate restTemplate;

  @Autowired
  private ObjectMapper objectMapper;

  /**
   * Main validation method that performs comprehensive checking
   * 
   * @param sheetUrl The Google Sheets URL to validate
   * @return SheetAccessibilityInfo containing detailed validation results
   */
  public SheetAccessibilityInfo validateAndCheckAccessibility(String sheetUrl) {
    log.info("Starting comprehensive validation for sheet: {}", sheetUrl);

    // Step 1: Basic URL format validation
    ValidationResult basicValidation = validateUrlFormat(sheetUrl);
    if (!basicValidation.isValid()) {
      return createInaccessibleResponse(basicValidation.getMessage(),
          "Please provide a valid Google Sheets URL");
    }

    String spreadsheetId = basicValidation.getSpreadsheetId();
    log.debug("Extracted spreadsheet ID: {}", spreadsheetId);

    // Step 2: Check public accessibility via OpenSheet API
    AccessibilityCheckResult publicCheck = checkPublicAccessibility(spreadsheetId);
    if (publicCheck.isAccessible()) {
      boolean hasPublicIndicators = hasPublicSharingIndicators(sheetUrl);

      return SheetAccessibilityInfo.builder().isAccessible(true).isPublic(true)
          .accessMethod("OPENSHEET_API").message("Sheet is publicly accessible")
          .costImplication("Free - no API quota usage")
          .userRecommendation(hasPublicIndicators ? null
              : "Consider adding sharing parameters to URL for better performance")
          .build();
    }

    // Step 3: Check private accessibility via Google Sheets API
    if (googleApiKey == null || googleApiKey.trim().isEmpty()) {
      return createInaccessibleResponse(
          "Sheet appears to be private and no Google API key is configured",
          "Either make the sheet public or configure Google API key");
    }

    AccessibilityCheckResult privateCheck = checkPrivateAccessibility(spreadsheetId);
    if (privateCheck.isAccessible()) {
      return SheetAccessibilityInfo.builder().isAccessible(true).isPublic(false)
          .accessMethod("GOOGLE_SHEETS_API")
          .message("Sheet is accessible via Google Sheets API (private)")
          .costImplication("Uses Google API quota")
          .userRecommendation("Consider making sheet public to avoid API quota usage").build();
    }

    // Step 4: Not accessible
    return createInaccessibleResponse("Sheet is not accessible with current permissions",
        "Check sheet permissions or contact sheet owner");
  }

  /**
   * Quick validation of URL format only
   * 
   * @param sheetUrl The URL to validate
   * @return true if URL format is valid
   */
  public boolean isValidUrlFormat(String sheetUrl) {
    return validateUrlFormat(sheetUrl).isValid();
  }

  /**
   * Quick check if sheet is publicly accessible
   * 
   * @param sheetUrl The Google Sheets URL
   * @return true if sheet is publicly accessible
   */
  public boolean isSheetPublic(String sheetUrl) {
    if (!isValidUrlFormat(sheetUrl)) {
      return false;
    }

    String spreadsheetId = extractSpreadsheetId(sheetUrl);
    if (spreadsheetId == null) {
      return false;
    }

    return checkPublicAccessibility(spreadsheetId).isAccessible();
  }

  // Private helper methods

  /**
   * Extract spreadsheet ID from Google Sheets URL
   * 
   * @param sheetUrl The Google Sheets URL
   * @return The spreadsheet ID, or null if not found
   */
  public String extractSpreadsheetId(String sheetUrl) {
    if (sheetUrl == null || sheetUrl.trim().isEmpty()) {
      return null;
    }

    Matcher matcher = ID_REGEX.matcher(sheetUrl);
    if (matcher.find()) {
      String id = matcher.group(1);
      log.debug("Extracted spreadsheet ID: {} from URL: {}", id, sheetUrl);
      return id;
    }

    log.warn("Could not extract spreadsheet ID from URL: {}", sheetUrl);
    return null;
  }

  /**
   * Normalize Google Sheets URL to standard format
   * 
   * @param sheetUrl The original URL
   * @return Normalized URL, or original if normalization fails
   */
  public String normalizeUrl(String sheetUrl) {
    String spreadsheetId = extractSpreadsheetId(sheetUrl);
    if (spreadsheetId != null) {
      return "https://docs.google.com/spreadsheets/d/" + spreadsheetId + "/edit";
    }
    return sheetUrl;
  }

  /**
   * Validate basic URL format and extract spreadsheet ID
   */
  private ValidationResult validateUrlFormat(String sheetUrl) {
    if (sheetUrl == null || sheetUrl.trim().isEmpty()) {
      return ValidationResult.invalid("Sheet URL cannot be null or empty");
    }

    String trimmedUrl = sheetUrl.trim();

    // Check if it's a Google Sheets domain
    if (!DOMAIN_REGEX.matcher(trimmedUrl).find()) {
      return ValidationResult.invalid("URL must be from docs.google.com/spreadsheets domain");
    }

    // Extract and validate spreadsheet ID
    String spreadsheetId = extractSpreadsheetId(trimmedUrl);
    if (spreadsheetId == null) {
      return ValidationResult
          .invalid("Invalid Google Sheets URL format - cannot extract spreadsheet ID");
    }

    // Validate spreadsheet ID format
    if (!VALID_ID_REGEX.matcher(spreadsheetId).matches()) {
      return ValidationResult.invalid("Invalid spreadsheet ID format");
    }

    log.debug("Successfully validated Google Sheets URL with ID: {}", spreadsheetId);
    return ValidationResult.valid("Valid Google Sheets URL", spreadsheetId);
  }

  /**
   * Check if sheet is publicly accessible via OpenSheet API
   */
  private AccessibilityCheckResult checkPublicAccessibility(String spreadsheetId) {
    try {
      String url = "https://opensheet.elk.sh/" + spreadsheetId + "/Sheet1";
      log.debug("Testing public access via OpenSheet for spreadsheet ID: {}", spreadsheetId);

      String response = restTemplate.getForObject(url, String.class);
      JsonNode jsonNode = objectMapper.readTree(response);

      // Check if we got a valid JSON array (indicates successful public access)
      boolean isAccessible = jsonNode != null && jsonNode.isArray();
      log.debug("Public access test result for {}: {}", spreadsheetId, isAccessible);

      return AccessibilityCheckResult
          .accessible(isAccessible ? "Public access confirmed" : "No public access");

    } catch (Exception e) {
      log.debug("Sheet {} is not publicly accessible via OpenSheet: {}", spreadsheetId,
          e.getMessage());
      return AccessibilityCheckResult.notAccessible("OpenSheet API failed: " + e.getMessage());
    }
  }

  /**
   * Check if sheet is accessible via Google Sheets API
   */
  private AccessibilityCheckResult checkPrivateAccessibility(String spreadsheetId) {
    try {
      // Use spreadsheet metadata endpoint for lightweight check
      String url = String.format(
          "https://sheets.googleapis.com/v4/spreadsheets/%s?key=%s&fields=properties.title",
          spreadsheetId, googleApiKey);

      log.debug("Testing API access for spreadsheet ID: {}", spreadsheetId);

      String response = restTemplate.getForObject(url, String.class);
      JsonNode jsonNode = objectMapper.readTree(response);

      // Check if we got sheet metadata (indicates successful API access)
      boolean isAccessible = jsonNode != null && jsonNode.has("properties");
      log.debug("API access test result for {}: {}", spreadsheetId, isAccessible);

      return AccessibilityCheckResult
          .accessible(isAccessible ? "API access confirmed" : "No API access");

    } catch (Exception e) {
      log.debug("Sheet {} is not accessible via Google API: {}", spreadsheetId, e.getMessage());
      return AccessibilityCheckResult.notAccessible("Google API failed: " + e.getMessage());
    }
  }

  // Inner classes for validation results

  /**
   * Check if URL contains public sharing indicators
   */
  private boolean hasPublicSharingIndicators(String sheetUrl) {
    if (sheetUrl == null) {
      return false;
    }

    String lowerUrl = sheetUrl.toLowerCase();
    return lowerUrl.contains("usp=sharing") || lowerUrl.contains("sharing")
        || lowerUrl.contains("/edit#gid=") || lowerUrl.contains("/edit?usp=sharing");
  }

  /**
   * Create a standard inaccessible response
   */
  private SheetAccessibilityInfo createInaccessibleResponse(String message, String recommendation) {
    return SheetAccessibilityInfo.builder().isAccessible(false).isPublic(false).accessMethod(null)
        .message(message).userRecommendation(recommendation).build();
  }
}
