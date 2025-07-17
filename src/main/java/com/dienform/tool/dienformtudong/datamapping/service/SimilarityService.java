package com.dienform.tool.dienformtudong.datamapping.service;

public interface SimilarityService {

  /**
   * Calculate similarity between two text strings
   * 
   * @param text1 First text string
   * @param text2 Second text string
   * @return Similarity score between 0.0 and 1.0
   */
  double calculateSimilarity(String text1, String text2);
}