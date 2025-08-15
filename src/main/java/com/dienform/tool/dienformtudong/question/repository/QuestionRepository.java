package com.dienform.tool.dienformtudong.question.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.question.entity.Question;

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {
  List<Question> findByFormIdOrderByPosition(UUID formId);

  void deleteByFormId(UUID formId);

  void deleteByForm(Form form);

  @EntityGraph(attributePaths = {"options"})
  List<Question> findByForm(Form form);

  /**
   * Find question with options loaded to avoid LazyInitializationException
   */
  @EntityGraph(attributePaths = {"options"})
  Optional<Question> findWithOptionsById(UUID id);
}
