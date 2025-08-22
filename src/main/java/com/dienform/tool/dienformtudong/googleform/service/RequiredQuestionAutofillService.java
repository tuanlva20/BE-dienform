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

  /**
   * Check if the Next button is ready to be clicked (enabled and visible)
   *
   * @param driver active Selenium WebDriver at current section
   * @return true if Next button is ready, false otherwise
   */
  boolean isNextButtonReady(WebDriver driver);

  /**
   * Clear autofill tracking (call this when starting a new form)
   */
  void clearAutofillTracking();

  /**
   * Check if we're stuck in the same section (to avoid infinite loops)
   *
   * @param driver active Selenium WebDriver at current section
   * @return true if stuck in same section, false otherwise
   */
  boolean isStuckInSameSection(WebDriver driver);

  /**
   * Validate that all required questions are properly filled before proceeding
   *
   * @param driver active Selenium WebDriver at current section
   * @return ValidationResult with status and error details
   */
  ValidationResult validateRequiredQuestions(WebDriver driver);
}


