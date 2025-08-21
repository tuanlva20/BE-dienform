package com.dienform.tool.dienformtudong.googleform.service;

import java.util.List;

/**
 * Navigate Google Form sections using Selenium and capture HTML per section.
 */
public interface SectionNavigationService {

  /**
   * Metadata for a single section.
   */
  class SectionMetadata {
    private final int sectionIndex;
    private final String sectionTitle;
    private final String sectionDescription;

    public SectionMetadata(int sectionIndex, String sectionTitle, String sectionDescription) {
      this.sectionIndex = sectionIndex;
      this.sectionTitle = sectionTitle;
      this.sectionDescription = sectionDescription;
    }

    public int getSectionIndex() {
      return sectionIndex;
    }

    public String getSectionTitle() {
      return sectionTitle;
    }

    public String getSectionDescription() {
      return sectionDescription;
    }
  }

  /**
   * Result class for combined section navigation
   */
  class SectionNavigationResult {
    private final List<String> sectionHtmls;
    private final List<SectionMetadata> sectionMetadata;

    public SectionNavigationResult(List<String> sectionHtmls,
        List<SectionMetadata> sectionMetadata) {
      this.sectionHtmls = sectionHtmls;
      this.sectionMetadata = sectionMetadata;
    }

    public List<String> getSectionHtmls() {
      return sectionHtmls;
    }

    public List<SectionMetadata> getSectionMetadata() {
      return sectionMetadata;
    }
  }

  /**
   * Use Selenium to navigate through sections by clicking the Next ("Tiếp") button until the Submit
   * ("Gửi") button appears. For each section, capture the full page HTML.
   *
   * Behavior: - If the first page does NOT have a Next button, return null so caller can fallback
   * to single-section logic (HTTP + parser). - Never click the Submit ("Gửi") button.
   *
   * @param formUrl public form URL
   * @return list of HTML documents per section, or null if no Next button is found on first page
   */
  List<String> captureSectionHtmls(String formUrl);

  /**
   * Extract section metadata (title and description) for each section after the first.
   * Implementation should skip the first section and only capture subsequent sections.
   *
   * @param formUrl public form URL
   * @return list of section metadata, or null if multi-section navigation is not available
   */
  List<SectionMetadata> captureSectionMetadata(String formUrl);

  /**
   * Fill a multi-section Google Form using provided selections. The filling logic for each section
   * matches the single-section flow, with added navigation: click "Tiếp" (Next) after finishing a
   * section until the "Gửi" (Submit) button appears, then submit.
   *
   * Prioritize resolving questions using section-related metadata in additionalData (e.g.,
   * section_index/position) before title-based strategies.
   *
   * @param formUrl public form URL
   * @param selections map of questions to selected options
   * @param humanLike whether to simulate human-like behavior
   * @return true if submitted successfully, false otherwise
   */
  boolean fillSections(String formUrl,
      java.util.Map<com.dienform.tool.dienformtudong.question.entity.Question, com.dienform.tool.dienformtudong.question.entity.QuestionOption> selections,
      boolean humanLike);

  /**
   * Capture both section HTMLs and metadata in a single browser session. This method optimizes
   * performance by avoiding multiple browser sessions.
   *
   * @param formUrl public form URL
   * @return SectionNavigationResult containing both HTMLs and metadata, or null if no Next button
   *         is found on first page
   */
  SectionNavigationResult captureSectionData(String formUrl);
}


