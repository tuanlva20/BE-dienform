package com.dienform.tool.dienformtudong.answerdistribution.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;

@Repository
public interface AnswerDistributionRepository extends JpaRepository<AnswerDistribution, UUID> {
    /**
     * Find answer distributions with question and option loaded to avoid
     * LazyInitializationException
     */
    @EntityGraph(attributePaths = {"question", "question.options", "option"})
    @Query("SELECT ad FROM AnswerDistribution ad WHERE ad.fillRequest.id = ?1")
    List<AnswerDistribution> findByFillRequestId(UUID fillRequestId);

    List<AnswerDistribution> findByQuestionId(UUID questionId);

    void deleteByFillRequestId(UUID fillRequestId);
}
