package com.dienform.tool.dienformtudong.question.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import java.util.List;
import java.util.UUID;

@Repository
public interface QuestionOptionRepository extends JpaRepository<QuestionOption, UUID> {
    List<QuestionOption> findByQuestionIdOrderByPosition(UUID questionId);
    void deleteByQuestionId(UUID questionId);
}
