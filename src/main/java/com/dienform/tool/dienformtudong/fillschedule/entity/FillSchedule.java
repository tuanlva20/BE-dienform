package com.dienform.tool.dienformtudong.fillschedule.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import com.dienform.common.entity.AuditEntity;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fill_schedule")
public class FillSchedule extends AuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // @Column(name = "fill_request_id", nullable = false)
    // private UUID fillRequestId;

    @Column(name = "is_scheduled", nullable = false)
    private boolean isScheduled;

    @Column(name = "is_dynamic_by_time", nullable = false)
    private boolean isDynamicByTime;

    @Column(name = "timezone")
    private String timezone;

    @Column(name = "min_interval")
    private Integer minInterval;

    @Column(name = "max_interval")
    private Integer maxInterval;

    @Column(name = "time_windows")
    private String timeWindows;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "batch_size")
    private Integer batchSize;

    @Column(name = "current_batch")
    private Integer currentBatch;

    @Column(name = "total_batches")
    private Integer totalBatches;

    @Column(name = "schedule_data", columnDefinition = "TEXT")
    private String scheduleData;

    @Column(name = "estimated_completion_date")
    private LocalDateTime estimatedCompletionDate;

    @OneToOne(fetch = FetchType.LAZY)
    private FillRequest fillRequest;

}
