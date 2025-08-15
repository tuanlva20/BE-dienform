package com.dienform.tool.dienformtudong.surveyexecution.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.form.enums.FormStatusEnum;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SesstionExecution;

@Repository
public interface SessionExecutionRepository extends JpaRepository<SesstionExecution, UUID> {

  List<SesstionExecution> findByFormId(UUID formId);

  List<SesstionExecution> findByFillRequestId(UUID fillRequestId);

  SesstionExecution findTopByFillRequestIdOrderByStartTimeDesc(UUID fillRequestId);

  @Modifying
  @Transactional
  @Query("UPDATE SesstionExecution s SET s.endTime = :endTime, s.successfulExecutions = :successful, s.failedExecutions = :failed, s.status = :status WHERE s.id = :id")
  int updateFinalState(@Param("id") UUID id, @Param("endTime") LocalDateTime endTime,
      @Param("successful") int successful, @Param("failed") int failed,
      @Param("status") FormStatusEnum status);
}
