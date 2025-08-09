package com.dienform.tool.dienformtudong.question.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;

@Repository
public interface QuestionOptionRepository extends JpaRepository<QuestionOption, UUID> {
    @EntityGraph(attributePaths = {"subOptions"})
    List<QuestionOption> findByQuestionIdOrderByPosition(UUID questionId);

    void deleteByQuestion(Question question);
}
