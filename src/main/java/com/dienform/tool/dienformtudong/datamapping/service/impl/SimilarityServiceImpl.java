package com.dienform.tool.dienformtudong.datamapping.service.impl;

import org.springframework.stereotype.Service;

import com.dienform.tool.dienformtudong.datamapping.service.SimilarityService;

@Service
public class SimilarityServiceImpl implements SimilarityService {

  @Override
  public double calculateSimilarity(String text1, String text2) {
    if (text1 == null || text2 == null) {
      return 0.0;
    }

    // Normalize strings
    String s1 = text1.toLowerCase().trim();
    String s2 = text2.toLowerCase().trim();

    if (s1.equals(s2)) {
      return 1.0;
    }

    // Use Levenshtein distance
    int distance = levenshteinDistance(s1, s2);
    int maxLength = Math.max(s1.length(), s2.length());

    if (maxLength == 0) {
      return 1.0;
    }

    double similarity = 1.0 - (double) distance / maxLength;

    // Bonus for partial matches
    if (s1.contains(s2) || s2.contains(s1)) {
      similarity += 0.1;
    }

    return Math.min(1.0, similarity);
  }

  private int levenshteinDistance(String s1, String s2) {
    int len1 = s1.length();
    int len2 = s2.length();

    int[][] dp = new int[len1 + 1][len2 + 1];

    for (int i = 0; i <= len1; i++) {
      dp[i][0] = i;
    }

    for (int j = 0; j <= len2; j++) {
      dp[0][j] = j;
    }

    for (int i = 1; i <= len1; i++) {
      for (int j = 1; j <= len2; j++) {
        if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
          dp[i][j] = dp[i - 1][j - 1];
        } else {
          dp[i][j] = Math.min(
              Math.min(dp[i - 1][j], dp[i][j - 1]),
              dp[i - 1][j - 1]) + 1;
        }
      }
    }

    return dp[len1][len2];
  }
}