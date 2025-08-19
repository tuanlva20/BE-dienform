package com.dienform.tool.dienformtudong.googleform.service;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;

/**
 * Helper service for form filling operations that can be reused across different implementations
 */
public interface FormFillingHelper {

  /**
   * Resolve question element using existing logic from GoogleFormServiceImpl
   * 
   * @param driver WebDriver instance
   * @param formUrl URL of the form
   * @param question Question entity
   * @return WebElement for the question, or null if not found
   */
  WebElement resolveQuestionElement(WebDriver driver, String formUrl, Question question);

  /**
   * Fill question by type using existing logic from GoogleFormServiceImpl
   * 
   * @param driver WebDriver instance
   * @param questionElement WebElement for the question
   * @param question Question entity
   * @param option Selected option
   * @param humanLike Whether to simulate human-like behavior
   * @return true if successful, false otherwise
   */
  boolean fillQuestionByType(WebDriver driver, WebElement questionElement, Question question,
      QuestionOption option, boolean humanLike);
}

