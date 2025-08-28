package com.dienform.tool.dienformtudong.fillrequest.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.dienform.common.entity.AuditEntity;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.fillschedule.entity.FillSchedule;
import com.dienform.tool.dienformtudong.form.entity.Form;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@org.hibernate.annotations.DynamicUpdate
@org.hibernate.annotations.DynamicInsert
@Table(name = "fill_request")
@EqualsAndHashCode(callSuper = true)
public class FillRequest extends AuditEntity {

    @Column(name = "survey_count", nullable = false)
    private int surveyCount;

    @Builder.Default
    @Column(name = "completed_survey", nullable = false)
    private int completedSurvey = 0;

    @Builder.Default
    @Column(name = "failed_survey", nullable = false)
    private int failedSurvey = 0;

    @Column(name = "price_per_survey", nullable = false)
    private BigDecimal pricePerSurvey;

    @Column(name = "total_price", nullable = false)
    private BigDecimal totalPrice;

    @Column(name = "is_human_like", nullable = false)
    private boolean humanLike;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Column(name = "estimated_completion_date")
    private LocalDateTime estimatedCompletionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FillRequestStatusEnum status;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 0;

    @Column(name = "queue_position")
    private Integer queuePosition;

    @Column(name = "queued_at")
    private LocalDateTime queuedAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private Integer maxRetries = 3;

    @OneToMany(mappedBy = "fillRequest")
    private List<AnswerDistribution> answerDistributions = new ArrayList<>();

    @OneToOne(mappedBy = "fillRequest")
    private FillSchedule fillSchedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_id", nullable = false)
    private Form form;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        super.onCreate();
        if (totalPrice == null && pricePerSurvey != null) {
            totalPrice = pricePerSurvey.multiply(BigDecimal.valueOf(surveyCount));
        }
        if (completedSurvey < 0) {
            completedSurvey = 0;
        }
    }
}
