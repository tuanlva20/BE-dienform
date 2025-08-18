package com.dienform.tool.dienformtudong.googleform.service;

import org.openqa.selenium.WebDriver;

/**
 * Quickly satisfies required questions in the current section to allow clicking Next. Must not
 * click the Submit button.
 */
public interface RequiredQuestionAutofillService {

  /**
   * Try to satisfy required questions in the current section with minimal inputs (e.g., select
   * first radio/checkbox, choose first option in listbox, fill "N/A" in text).
   *
   * @param driver active Selenium WebDriver at current section
   * @return true if likely satisfied, false otherwise
   */
  boolean satisfyRequiredQuestions(WebDriver driver);
}


