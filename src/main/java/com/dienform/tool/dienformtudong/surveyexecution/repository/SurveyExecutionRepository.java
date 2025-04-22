package com.dienform.tool.dienformtudong.surveyexecution.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SurveyExecution;

@Repository
public interface SurveyExecutionRepository extends JpaRepository<SurveyExecution, UUID> {
    Page<SurveyExecution> findByFillRequestId(UUID fillRequestId, Pageable pageable);

    List<SurveyExecution> findByFillRequestIdAndStatus(UUID fillRequestId, String status);

    long countByFillRequestIdAndStatus(UUID fillRequestId, String status);

    @Query("SELECT se FROM SurveyExecution se WHERE se.fillRequest.id = :fillRequestId")
    Page<SurveyExecution> findByFormId(@Param("fillRequestId") UUID formId, Pageable pageable);

    @Query("SELECT se FROM SurveyExecution se WHERE se.fillRequest.id = :fillRequestId AND se.executionTime BETWEEN :startTime AND :endTime")
    List<SurveyExecution> findByFillRequestAndExecutionTimeBetween(
        @Param("fillRequestId") UUID fillRequestId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime);
}
