package com.dienform.tool.dienformtudong.surveyexecution.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SesstionExecution;

@Repository
public interface SessionExecutionRepository extends JpaRepository<SesstionExecution, UUID> {

  List<SesstionExecution> findByFormId(UUID formId);

  List<SesstionExecution> findByFillRequestId(UUID fillRequestId);

  SesstionExecution findTopByFillRequestIdOrderByStartTimeDesc(UUID fillRequestId);
}
