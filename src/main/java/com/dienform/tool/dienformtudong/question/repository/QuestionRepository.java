package com.dienform.tool.dienformtudong.question.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.question.entity.Question;
import java.util.List;
import java.util.UUID;

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByFormIdOrderByPosition(UUID formId);
    void deleteByFormId(UUID formId);
}
