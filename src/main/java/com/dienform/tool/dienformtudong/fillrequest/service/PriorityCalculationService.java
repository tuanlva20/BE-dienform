package com.dienform.tool.dienformtudong.fillrequest.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for calculating priority based on various factors including creation date
 * Implements age-based priority to prevent starvation and ensure fairness
 */
@Service
@Slf4j
public class PriorityCalculationService {

    private static final AtomicInteger requestCounter = new AtomicInteger(0);

    /**
     * Calculate priority based on age (time since creation) and business logic
     * Higher priority for older requests to prevent starvation
     */
    public int calculateAgeBasedPriority(LocalDateTime createdAt) {
        LocalDateTime now = LocalDateTime.now();
        long hoursWaited = Duration.between(createdAt, now).toHours();
        
        // Priority increases with waiting time to prevent starvation
        if (hoursWaited >= 24) return 20;      // Chờ > 24h: Priority cao nhất
        if (hoursWaited >= 12) return 16;      // Chờ > 12h: Priority cao
        if (hoursWaited >= 6) return 12;       // Chờ > 6h: Priority trung bình cao
        if (hoursWaited >= 2) return 8;        // Chờ > 2h: Priority trung bình
        if (hoursWaited >= 1) return 4;        // Chờ > 1h: Priority thấp
        return 0;                               // Chờ < 1h: Priority mặc định
    }

    /**
     * Calculate priority with linear formula
     * Priority increases by 1 point every 30 minutes
     */
    public int calculateLinearAgePriority(LocalDateTime createdAt) {
        LocalDateTime now = LocalDateTime.now();
        long minutesWaited = Duration.between(createdAt, now).toMinutes();
        
        // Priority increases 1 point every 30 minutes
        int priority = (int) (minutesWaited / 30);
        
        // Cap at maximum priority of 20
        return Math.min(priority, 20);
    }

    /**
     * Calculate priority with logarithmic formula (slower increase)
     */
    public int calculateLogarithmicAgePriority(LocalDateTime createdAt) {
        LocalDateTime now = LocalDateTime.now();
        long hoursWaited = Duration.between(createdAt, now).toHours();
        
        // Priority increases logarithmically: log(hours + 1) * 3
        double logPriority = Math.log(hoursWaited + 1) * 3;
        
        return Math.min((int) logPriority, 15);
    }

    /**
     * Calculate hybrid priority combining age and business factors
     */
    public int calculateHybridPriority(LocalDateTime createdAt, int basePriority, 
                                      boolean isHighValue, boolean isUrgent) {
        int agePriority = calculateAgeBasedPriority(createdAt);
        int businessPriority = calculateBusinessPriority(basePriority, isHighValue, isUrgent);
        
        // Combine: 70% age + 30% business
        return (int) (agePriority * 0.7 + businessPriority * 0.3);
    }

    /**
     * Calculate business priority based on various factors
     */
    private int calculateBusinessPriority(int basePriority, boolean isHighValue, boolean isUrgent) {
        int priority = basePriority;
        
        // High value requests get priority boost
        if (isHighValue) {
            priority += 5;
        }
        
        // Urgent requests get priority boost
        if (isUrgent) {
            priority += 3;
        }
        
        return Math.min(priority, 10);
    }

    /**
     * Get priority level description
     */
    public String getPriorityLevelDescription(int priority) {
        if (priority >= 15) return "CRITICAL";
        if (priority >= 10) return "HIGH";
        if (priority >= 5) return "MEDIUM";
        if (priority >= 1) return "LOW";
        return "DEFAULT";
    }

    /**
     * Calculate estimated wait time based on current priority
     */
    public int calculateEstimatedWaitTime(int priority, int queuePosition) {
        // Base wait time per priority level (in minutes)
        int baseWaitTime = switch (priority) {
            case 15, 16, 17, 18, 19, 20 -> 1;   // Critical: 1 minute
            case 10, 11, 12, 13, 14 -> 5;       // High: 5 minutes
            case 5, 6, 7, 8, 9 -> 15;           // Medium: 15 minutes
            case 1, 2, 3, 4 -> 30;              // Low: 30 minutes
            default -> 60;                      // Default: 60 minutes
        };
        
        // Add queue position factor
        return baseWaitTime + (queuePosition * 2);
    }
}
