package com.dienform.tool.dienformtudong.answerdistribution.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import java.util.List;
import java.util.UUID;

@Repository
public interface AnswerDistributionRepository extends JpaRepository<AnswerDistribution, UUID> {
    List<AnswerDistribution> findByFillRequestId(UUID fillRequestId);
    List<AnswerDistribution> findByQuestionId(UUID questionId);
    void deleteByFillRequestId(UUID fillRequestId);
}