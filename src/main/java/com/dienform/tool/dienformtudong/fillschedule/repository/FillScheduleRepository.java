package com.dienform.tool.dienformtudong.fillschedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.fillschedule.entity.FillSchedule;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FillScheduleRepository extends JpaRepository<FillSchedule, UUID> {
    Optional<FillSchedule> findByFillRequestId(UUID fillRequestId);
    void deleteByFillRequestId(UUID fillRequestId);
}