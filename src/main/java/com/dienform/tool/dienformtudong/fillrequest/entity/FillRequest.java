package com.dienform.tool.dienformtudong.fillrequest.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.fillschedule.entity.FillSchedule;
import com.dienform.tool.dienformtudong.form.entity.Form;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
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
@org.hibernate.annotations.DynamicUpdate
@Table(name = "fill_request")
public class FillRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "survey_count", nullable = false)
    private int surveyCount;

    @Builder.Default
    @Column(name = "completed_survey", nullable = false)
    private int completedSurvey = 0;

    @Column(name = "price_per_survey", nullable = false)
    private BigDecimal pricePerSurvey;

    @Column(name = "total_price", nullable = false)
    private BigDecimal totalPrice;

    @Column(name = "is_human_like", nullable = false)
    private boolean humanLike;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FillRequestStatusEnum status;

    @OneToMany(mappedBy = "fillRequest")
    private List<AnswerDistribution> answerDistributions = new ArrayList<>();

    @OneToOne(mappedBy = "fillRequest")
    private FillSchedule fillSchedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_id", nullable = false)
    private Form form;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (totalPrice == null && pricePerSurvey != null) {
            totalPrice = pricePerSurvey.multiply(BigDecimal.valueOf(surveyCount));
        }
        if (completedSurvey < 0) {
            completedSurvey = 0;
        }
    }
}
