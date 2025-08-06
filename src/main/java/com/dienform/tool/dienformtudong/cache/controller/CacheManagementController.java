package com.dienform.tool.dienformtudong.cache.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.tool.dienformtudong.googleform.service.impl.GoogleFormServiceImpl;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
public class CacheManagementController {
  private final GoogleFormServiceImpl googleFormServiceImpl;

  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> getCacheStatus() {
    Map<String, Object> status = new HashMap<>();
    status.put("questionElementsCache_size",
        googleFormServiceImpl.getQuestionElementsCache().size());
    status.put("formQuestionMapCache_size", googleFormServiceImpl.getFormQuestionMapCache().size());
    status.put("questionMappingCache_size", googleFormServiceImpl.getQuestionMappingCache().size());
    status.put("formQuestionsCache_size", googleFormServiceImpl.getFormQuestionsCache().size());
    return ResponseEntity.ok(status);
  }

  @GetMapping("/keys")
  public ResponseEntity<Map<String, Object>> getCacheKeys() {
    Map<String, Object> keys = new HashMap<>();
    keys.put("questionElementsCache_keys",
        googleFormServiceImpl.getQuestionElementsCache().keySet());
    keys.put("formQuestionMapCache_keys", googleFormServiceImpl.getFormQuestionMapCache().keySet());
    keys.put("questionMappingCache_keys", googleFormServiceImpl.getQuestionMappingCache().keySet());
    keys.put("formQuestionsCache_keys", googleFormServiceImpl.getFormQuestionsCache().keySet());
    return ResponseEntity.ok(keys);
  }

  @PostMapping("/clear")
  public ResponseEntity<Map<String, Object>> clearAllCaches() {
    googleFormServiceImpl.clearCaches();
    Map<String, Object> result = new HashMap<>();
    result.put("message", "All caches cleared successfully");
    return ResponseEntity.ok(result);
  }

  @GetMapping("/detail")
  public ResponseEntity<Object> getCacheDetail(@RequestParam String type) {
    switch (type) {
      case "questionElements":
        return ResponseEntity.ok(
            googleFormServiceImpl.getQuestionElementsCache().entrySet().stream().collect(Collectors
                .toMap(Map.Entry::getKey, e -> e.getValue() == null ? 0 : e.getValue().size())));
      case "questionMap":
        return ResponseEntity.ok(
            googleFormServiceImpl.getFormQuestionMapCache().entrySet().stream().collect(Collectors
                .toMap(Map.Entry::getKey, e -> e.getValue() == null ? 0 : e.getValue().size())));
      case "questionMapping":
        return ResponseEntity.ok(googleFormServiceImpl.getQuestionMappingCache().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue() == null ? "null" : e.getValue().toString())));
      case "questions":
        return ResponseEntity
            .ok(googleFormServiceImpl.getFormQuestionsCache().entrySet().stream().collect(Collectors
                .toMap(Map.Entry::getKey, e -> e.getValue() == null ? 0 : e.getValue().size())));
      default:
        return ResponseEntity.badRequest().body("Unknown cache type");
    }
  }

  @GetMapping("/all")
  public ResponseEntity<Map<String, Object>> getAllCacheData() {
    Map<String, Object> all = new HashMap<>();
    // questionElementsCache: key -> số lượng phần tử
    all.put("questionElementsCache",
        googleFormServiceImpl.getQuestionElementsCache().entrySet().stream().collect(Collectors
            .toMap(Map.Entry::getKey, e -> e.getValue() == null ? 0 : e.getValue().size())));
    // formQuestionMapCache: key -> số lượng phần tử
    all.put("formQuestionMapCache",
        googleFormServiceImpl.getFormQuestionMapCache().entrySet().stream().collect(Collectors
            .toMap(Map.Entry::getKey, e -> e.getValue() == null ? 0 : e.getValue().size())));
    // questionMappingCache: key -> string value
    all.put("questionMappingCache",
        googleFormServiceImpl.getQuestionMappingCache().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue() == null ? "null" : e.getValue().toString())));
    // formQuestionsCache: key -> số lượng phần tử
    all.put("formQuestionsCache",
        googleFormServiceImpl.getFormQuestionsCache().entrySet().stream().collect(Collectors
            .toMap(Map.Entry::getKey, e -> e.getValue() == null ? 0 : e.getValue().size())));
    return ResponseEntity.ok(all);
  }
}
