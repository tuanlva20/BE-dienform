package com.dienform.tool.dienformtudong.fillrequest.entity;

import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.fillschedule.entity.FillSchedule;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SurveyExecution;
import com.dienform.tool.dienformtudong.form.entity.Form;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fill_request")
public class FillRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "survey_count", nullable = false)
    private int surveyCount;

    @Column(name = "price_per_survey", nullable = false)
    private BigDecimal pricePerSurvey;

    @Column(name = "total_price", nullable = false)
    private BigDecimal totalPrice;

    @Column(name = "is_human_like", nullable = false)
    private boolean humanLike;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "status", nullable = false)
    private String status;

    @OneToMany(mappedBy = "fillRequest")
    private Set<AnswerDistribution> answerDistributions = new LinkedHashSet<>();

    @OneToOne(mappedBy = "fillRequest")
    private FillSchedule fillSchedule;

    @OneToMany(mappedBy = "fillRequest")
    private Set<SurveyExecution> surveyExecutions = new LinkedHashSet<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_id", updatable = false, nullable = false)
    private Form form;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (totalPrice == null && pricePerSurvey != null) {
            totalPrice = pricePerSurvey.multiply(BigDecimal.valueOf(surveyCount));
        }
    }
}
