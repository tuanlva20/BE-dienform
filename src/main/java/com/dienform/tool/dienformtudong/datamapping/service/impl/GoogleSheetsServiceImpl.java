package com.dienform.tool.dienformtudong.datamapping.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.dienform.tool.dienformtudong.datamapping.dto.response.SheetAccessibilityInfo;
import com.dienform.tool.dienformtudong.datamapping.service.GoogleSheetsService;
import com.dienform.tool.dienformtudong.datamapping.validator.GoogleSheetsValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of GoogleSheetsService with comprehensive validation and accessibility checking
 * Uses GoogleSheetsValidator for all validation logic Supports both public sheets (via OpenSheet
 * API) and private sheets (via Google Sheets API)
 */
@Service
@Slf4j
public class GoogleSheetsServiceImpl implements GoogleSheetsService {

  @Value("${google.api.key:}")
  private String googleApiKey;

  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;
  private final GoogleSheetsValidator validator;

  @Autowired
  public GoogleSheetsServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper,
      GoogleSheetsValidator validator) {
    this.restTemplate = restTemplate;
    this.objectMapper = objectMapper;
    this.validator = validator;
  }

  /**
   * Validates Google Sheets URL and checks accessibility status Delegates to GoogleSheetsValidator
   * for comprehensive checking
   */
  @Override
  public SheetAccessibilityInfo validateAndCheckAccessibility(String sheetLink) {
    log.info("Delegating validation to GoogleSheetsValidator for: {}", sheetLink);
    return validator.validateAndCheckAccessibility(sheetLink);
  }

  /**
   * Check if a Google Sheets URL is valid format Delegates to GoogleSheetsValidator
   */
  @Override
  public boolean isValidGoogleSheetsUrl(String sheetLink) {
    return validator.isValidUrlFormat(sheetLink);
  }

  /**
   * Check if a sheet is publicly accessible (no API key required) Delegates to
   * GoogleSheetsValidator
   */
  @Override
  public boolean isSheetPublic(String sheetLink) {
    return validator.isSheetPublic(sheetLink);
  }

  /**
   * Get column headers from Google Sheets Uses optimized approach based on validation results
   */
  @Override
  public List<String> getSheetColumns(String sheetLink) throws Exception {
    try {
      // First validate and check accessibility for optimal approach
      SheetAccessibilityInfo accessInfo = validator.validateAndCheckAccessibility(sheetLink);

      if (!accessInfo.isAccessible()) {
        throw new RuntimeException("Sheet not accessible: " + accessInfo.getMessage());
      }

      String spreadsheetId = validator.extractSpreadsheetId(sheetLink);
      log.info("Extracting columns from sheet: {} using method: {}", spreadsheetId,
          accessInfo.getAccessMethod());

      // Use the optimal method based on accessibility check
      if ("OPENSHEET_API".equals(accessInfo.getAccessMethod())) {
        return getColumnsFromOpenSheet(spreadsheetId);
      } else if ("GOOGLE_SHEETS_API".equals(accessInfo.getAccessMethod())) {
        return getColumnsFromGoogleAPI(spreadsheetId);
      } else {
        // Fallback to the old trial-and-error approach
        return getColumnsWithFallback(spreadsheetId);
      }

    } catch (Exception e) {
      log.error("Error accessing Google Sheets", e);
      throw new RuntimeException("Không thể truy cập Google Sheets: " + e.getMessage(), e);
    }
  }

  /**
   * Get all data from Google Sheets Uses optimized approach based on validation results
   */
  @Override
  public List<Map<String, Object>> getSheetData(String sheetLink) throws Exception {
    try {
      // First validate and check accessibility for optimal approach
      SheetAccessibilityInfo accessInfo = validator.validateAndCheckAccessibility(sheetLink);

      if (!accessInfo.isAccessible()) {
        throw new RuntimeException("Sheet not accessible: " + accessInfo.getMessage());
      }

      String spreadsheetId = validator.extractSpreadsheetId(sheetLink);
      log.info("Reading data from sheet: {} using method: {}", spreadsheetId,
          accessInfo.getAccessMethod());

      // Use the optimal method based on accessibility check
      if ("OPENSHEET_API".equals(accessInfo.getAccessMethod())) {
        return getDataFromOpenSheet(spreadsheetId);
      } else if ("GOOGLE_SHEETS_API".equals(accessInfo.getAccessMethod())) {
        return getDataFromGoogleAPI(spreadsheetId);
      } else {
        // Fallback to the old trial-and-error approach
        return getDataWithFallback(spreadsheetId);
      }

    } catch (Exception e) {
      log.error("Error reading Google Sheets data", e);
      throw new RuntimeException("Không thể đọc dữ liệu từ Google Sheets: " + e.getMessage(), e);
    }
  }

  // Private helper methods for data reading

  /**
   * Fallback method using trial-and-error approach (for backward compatibility)
   */
  private List<String> getColumnsWithFallback(String spreadsheetId) throws Exception {
    try {
      return getColumnsFromOpenSheet(spreadsheetId);
    } catch (Exception e) {
      log.warn("OpenSheet API failed, trying Google Sheets API: {}", e.getMessage());
      return getColumnsFromGoogleAPI(spreadsheetId);
    }
  }

  /**
   * Fallback method using trial-and-error approach (for backward compatibility)
   */
  private List<Map<String, Object>> getDataWithFallback(String spreadsheetId) throws Exception {
    try {
      return getDataFromOpenSheet(spreadsheetId);
    } catch (Exception e) {
      log.warn("OpenSheet API failed, trying Google Sheets API: {}", e.getMessage());
      return getDataFromGoogleAPI(spreadsheetId);
    }
  }

  /**
   * Get columns using OpenSheet API (no auth required)
   */
  private List<String> getColumnsFromOpenSheet(String spreadsheetId) throws Exception {
    String url = "https://opensheet.elk.sh/" + spreadsheetId + "/Sheet1";

    try {
      String response = restTemplate.getForObject(url, String.class);
      JsonNode jsonNode = objectMapper.readTree(response);

      if (jsonNode.isArray() && jsonNode.size() > 0) {
        JsonNode firstRow = jsonNode.get(0);
        List<String> columns = new ArrayList<>();
        firstRow.fieldNames().forEachRemaining(columns::add);
        return columns;
      }

      throw new RuntimeException("No data found in sheet");
    } catch (Exception e) {
      log.error("Error accessing OpenSheet API", e);
      throw e;
    }
  }

  /**
   * Get data using OpenSheet API (no auth required)
   */
  private List<Map<String, Object>> getDataFromOpenSheet(String spreadsheetId) throws Exception {
    String url = "https://opensheet.elk.sh/" + spreadsheetId + "/Sheet1";

    try {
      String response = restTemplate.getForObject(url, String.class);
      JsonNode jsonNode = objectMapper.readTree(response);

      List<Map<String, Object>> data = new ArrayList<>();

      if (jsonNode.isArray()) {
        for (JsonNode row : jsonNode) {
          Map<String, Object> rowMap = new HashMap<>();
          row.fields().forEachRemaining(entry -> {
            rowMap.put(entry.getKey(), entry.getValue().asText());
          });
          data.add(rowMap);
        }
      }

      return data;
    } catch (Exception e) {
      log.error("Error accessing OpenSheet API", e);
      throw e;
    }
  }

  /**
   * Get columns using Google Sheets API (requires API key)
   */
  private List<String> getColumnsFromGoogleAPI(String spreadsheetId) throws Exception {
    if (googleApiKey == null || googleApiKey.trim().isEmpty()) {
      throw new RuntimeException("Google API key is required for private sheets");
    }

    String url =
        String.format("https://sheets.googleapis.com/v4/spreadsheets/%s/values/A1:Z1?key=%s",
            spreadsheetId, googleApiKey);

    try {
      String response = restTemplate.getForObject(url, String.class);
      JsonNode jsonNode = objectMapper.readTree(response);

      JsonNode values = jsonNode.get("values");
      if (values != null && values.isArray() && values.size() > 0) {
        JsonNode firstRow = values.get(0);
        List<String> columns = new ArrayList<>();
        for (JsonNode cell : firstRow) {
          String cellValue = cell.asText().trim();
          if (!cellValue.isEmpty()) {
            columns.add(cellValue);
          }
        }
        return columns;
      }

      throw new RuntimeException("No header data found in sheet");
    } catch (Exception e) {
      log.error("Error accessing Google Sheets API", e);
      throw e;
    }
  }

  /**
   * Get data using Google Sheets API (requires API key)
   */
  private List<Map<String, Object>> getDataFromGoogleAPI(String spreadsheetId) throws Exception {
    if (googleApiKey == null || googleApiKey.trim().isEmpty()) {
      throw new RuntimeException("Google API key is required for private sheets");
    }

    String url = String.format("https://sheets.googleapis.com/v4/spreadsheets/%s/values/A:Z?key=%s",
        spreadsheetId, googleApiKey);

    try {
      String response = restTemplate.getForObject(url, String.class);
      JsonNode jsonNode = objectMapper.readTree(response);

      JsonNode values = jsonNode.get("values");
      if (values == null || !values.isArray() || values.size() < 2) {
        throw new RuntimeException("Google Sheets không có đủ dữ liệu (cần ít nhất 2 dòng)");
      }

      // First row is headers
      JsonNode headerRow = values.get(0);
      List<String> headers = new ArrayList<>();
      for (JsonNode cell : headerRow) {
        headers.add(cell.asText());
      }

      // Convert remaining rows to Map
      List<Map<String, Object>> data = new ArrayList<>();
      for (int i = 1; i < values.size(); i++) {
        JsonNode row = values.get(i);
        Map<String, Object> rowMap = new HashMap<>();

        for (int j = 0; j < headers.size() && j < row.size(); j++) {
          rowMap.put(headers.get(j), row.get(j).asText());
        }

        data.add(rowMap);
      }

      return data;
    } catch (Exception e) {
      log.error("Error accessing Google Sheets API", e);
      throw e;
    }
  }
}
