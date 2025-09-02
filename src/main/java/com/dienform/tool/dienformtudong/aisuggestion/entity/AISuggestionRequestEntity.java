package com.dienform.tool.dienformtudong.aisuggestion.entity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.dienform.common.util.DateTimeUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity for AI Suggestion requests in queue system Supports priority-based processing and status
 * tracking
 */
@Entity
@Table(name = "ai_suggestion_request")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AISuggestionRequestEntity {

    /**
     * Status enum for AI Suggestion requests
     */
    public enum AISuggestionStatus {
        QUEUED, // Request is in queue waiting to be processed
        PROCESSING, // Request is currently being processed
        COMPLETED, // Request completed successfully
        FAILED, // Request failed after max retries
        CANCELLED // Request was cancelled
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "form_id", nullable = false)
    private String formId;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 5;

    @Column(name = "queue_position")
    private Integer queuePosition;

    @Column(name = "queued_at")
    private LocalDateTime queuedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private AISuggestionStatus status = AISuggestionStatus.QUEUED;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private Integer maxRetries = 3;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "result_data", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> resultData;

    @Column(name = "request_data", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> requestData;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "processing_completed_at")
    private LocalDateTime processingCompletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = DateTimeUtil.now();
        updatedAt = DateTimeUtil.now();
        if (status == null) {
            status = AISuggestionStatus.QUEUED;
        }
        if (priority == null) {
            priority = 5;
        }
        if (retryCount == null) {
            retryCount = 0;
        }
        if (maxRetries == null) {
            maxRetries = 3;
        }
    }
}
