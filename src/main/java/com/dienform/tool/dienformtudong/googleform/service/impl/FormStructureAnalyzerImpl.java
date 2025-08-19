package com.dienform.tool.dienformtudong.googleform.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.googleform.dto.FormStructure;
import com.dienform.tool.dienformtudong.googleform.dto.FormStructureType;
import com.dienform.tool.dienformtudong.googleform.dto.QuestionInfo;
import com.dienform.tool.dienformtudong.googleform.dto.SectionInfo;
import com.dienform.tool.dienformtudong.googleform.service.FormStructureAnalyzer;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of FormStructureAnalyzer that analyzes form structure from database
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FormStructureAnalyzerImpl implements FormStructureAnalyzer {

  private final QuestionRepository questionRepository;

  @Override
  public FormStructure analyzeFormStructureFromDatabase(Form form) {
    log.debug("Analyzing form structure from database for form: {}", form.getId());

    List<Question> questions = questionRepository.findByForm(form);

    // Check if any question has section_index in additionalData
    boolean hasSections = questions.stream().anyMatch(
        q -> q.getAdditionalData() != null && q.getAdditionalData().containsKey("section_index"));

    if (!hasSections) {
      log.debug("Form {} is single-section form", form.getId());
      return FormStructure.builder().type(FormStructureType.SINGLE_SECTION)
          .sections(Collections.emptyList()).totalSections(1).hasNextButton(false)
          .hasSubmitButton(true).build();
    }

    // Analyze multi-section form
    log.debug("Form {} is multi-section form", form.getId());

    // Group questions by section index
    Map<Integer, List<Question>> questionsBySection = questions.stream().filter(
        q -> q.getAdditionalData() != null && q.getAdditionalData().containsKey("section_index"))
        .collect(Collectors.groupingBy(q -> {
          String sectionIndex = q.getAdditionalData().get("section_index");
          return Integer.parseInt(sectionIndex);
        }));

    List<SectionInfo> sections = new ArrayList<>();
    int maxSectionIndex =
        questionsBySection.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);

    for (int i = 1; i <= maxSectionIndex; i++) {
      List<Question> sectionQuestions = questionsBySection.getOrDefault(i, Collections.emptyList());
      if (!sectionQuestions.isEmpty()) {
        Question firstQuestion = sectionQuestions.get(0);
        String sectionTitle = firstQuestion.getAdditionalData().get("section_title");
        String sectionDescription = firstQuestion.getAdditionalData().get("section_description");

        SectionInfo sectionInfo = SectionInfo.builder().sectionIndex(i)
            .sectionTitle(sectionTitle != null ? sectionTitle : "Section " + i)
            .sectionDescription(sectionDescription)
            .questions(
                sectionQuestions.stream().map(this::mapToQuestionInfo).collect(Collectors.toList()))
            .isLastSection(i == maxSectionIndex).build();

        sections.add(sectionInfo);
        log.debug("Found section {}: {} with {} questions", i, sectionTitle,
            sectionQuestions.size());
      }
    }

    return FormStructure.builder().type(FormStructureType.MULTI_SECTION).sections(sections)
        .totalSections(sections.size()).hasNextButton(true).hasSubmitButton(true).build();
  }

  @Override
  public boolean hasSections(Form form) {
    List<Question> questions = questionRepository.findByForm(form);
    return questions.stream().anyMatch(
        q -> q.getAdditionalData() != null && q.getAdditionalData().containsKey("section_index"));
  }

  @Override
  public List<SectionInfo> getSectionInfoFromDatabase(Form form) {
    FormStructure structure = analyzeFormStructureFromDatabase(form);
    return structure.getSections();
  }

  /**
   * Map Question entity to QuestionInfo DTO
   */
  private QuestionInfo mapToQuestionInfo(Question question) {
    return QuestionInfo.builder().questionEntity(question).questionId(question.getId())
        .title(question.getTitle()).type(question.getType()).required(question.getRequired())
        .position(question.getPosition()).build();
  }
}

