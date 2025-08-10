package com.dienform.tool.dienformtudong.cache.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
public class CacheManagementController {
  private final GoogleFormService googleFormServiceImpl;

  /**
   * Get cache status with sizes of all caches
   */
  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> getCacheStatus() {
    Map<String, Object> status = new HashMap<>();
    try {
      // Deprecated caches removed; show only active caches
      status.put("formQuestionsCache_size", googleFormServiceImpl.getFormQuestionsCache().size());
      // formLocatorCache is internal; we can reflect via a method in service if exposed later
      status.put("timestamp", System.currentTimeMillis());
      return ResponseEntity.ok(status);
    } catch (Exception e) {
      status.put("error", "Failed to get cache status: " + e.getMessage());
      return ResponseEntity.internalServerError().body(status);
    }
  }

  /**
   * Get cache keys for all caches
   */
  @GetMapping("/keys")
  public ResponseEntity<Map<String, Object>> getCacheKeys() {
    Map<String, Object> keys = new HashMap<>();
    try {
      // Deprecated caches removed
      keys.put("formQuestionsCache_keys", googleFormServiceImpl.getFormQuestionsCache().keySet());
      keys.put("timestamp", System.currentTimeMillis());
      return ResponseEntity.ok(keys);
    } catch (Exception e) {
      keys.put("error", "Failed to get cache keys: " + e.getMessage());
      return ResponseEntity.internalServerError().body(keys);
    }
  }

  /**
   * Clear all caches
   */
  @PostMapping("/clear")
  public ResponseEntity<Map<String, Object>> clearAllCaches() {
    Map<String, Object> result = new HashMap<>();
    try {
      googleFormServiceImpl.clearCaches();
      result.put("message", "All caches cleared successfully");
      result.put("timestamp", System.currentTimeMillis());
      return ResponseEntity.ok(result);
    } catch (Exception e) {
      result.put("error", "Failed to clear caches: " + e.getMessage());
      return ResponseEntity.internalServerError().body(result);
    }
  }

  /**
   * Get detailed cache information for a specific cache type
   */
  @GetMapping("/detail")
  public ResponseEntity<Object> getCacheDetail(@RequestParam String type) {
    try {
      switch (type) {
        case "questionElements":
        case "questionMap":
        case "questionMapping":
          return ResponseEntity.badRequest().body("Deprecated cache type: " + type);
        case "questions":
          return ResponseEntity.ok(
              googleFormServiceImpl.getFormQuestionsCache().entrySet().stream().collect(Collectors
                  .toMap(Map.Entry::getKey, e -> e.getValue() == null ? 0 : e.getValue().size())));
        default:
          Map<String, Object> error = new HashMap<>();
          error.put("error", "Unknown cache type: " + type);
          error.put("validTypes", List.of("questions"));
          return ResponseEntity.badRequest().body(error);
      }
    } catch (Exception e) {
      Map<String, Object> error = new HashMap<>();
      error.put("error", "Failed to get cache detail: " + e.getMessage());
      return ResponseEntity.internalServerError().body(error);
    }
  }

  /**
   * Get all cache data with detailed information
   */
  @GetMapping("/all")
  public ResponseEntity<Map<String, Object>> getAllCacheData() {
    Map<String, Object> all = new HashMap<>();
    try {
      // Deprecated caches removed from detailed view
      // formQuestionsCache: key -> số lượng phần tử
      all.put("formQuestionsCache",
          googleFormServiceImpl.getFormQuestionsCache().entrySet().stream().collect(Collectors
              .toMap(Map.Entry::getKey, e -> e.getValue() == null ? 0 : e.getValue().size())));
      all.put("timestamp", System.currentTimeMillis());
      return ResponseEntity.ok(all);
    } catch (Exception e) {
      all.put("error", "Failed to get all cache data: " + e.getMessage());
      return ResponseEntity.internalServerError().body(all);
    }
  }
}
