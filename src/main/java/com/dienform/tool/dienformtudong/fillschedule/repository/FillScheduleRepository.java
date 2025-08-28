package com.dienform.tool.dienformtudong.fillschedule.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.fillschedule.entity.FillSchedule;

@Repository
public interface FillScheduleRepository extends JpaRepository<FillSchedule, UUID> {
    Optional<FillSchedule> findByFillRequestId(UUID fillRequestId);

    void deleteByFillRequestId(UUID fillRequestId);

    @Query("SELECT fs FROM FillSchedule fs WHERE fs.fillRequest.id = ?1 AND fs.currentBatch < fs.totalBatches")
    Optional<FillSchedule> findIncompleteSchedule(UUID fillRequestId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE FillSchedule fs SET fs.currentBatch = fs.currentBatch + 1 WHERE fs.id = ?1")
    int incrementCurrentBatch(UUID scheduleId);
}
