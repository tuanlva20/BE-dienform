package com.dienform.tool.dienformtudong.fillrequest.service;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for dynamically updating priorities of queued fill requests
 * Prevents starvation by increasing priority of older requests
 */
@Service
@Slf4j
public class DynamicPriorityService {

    @Autowired
    private FillRequestRepository fillRequestRepository;

    @Autowired
    private PriorityCalculationService priorityCalculationService;

    /**
     * Scheduled task to update priorities of queued requests every 5 minutes
     * Ensures older requests get higher priority to prevent starvation
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    @Transactional
    public void updateQueuedRequestPriorities() {
        try {
            List<FillRequest> queuedRequests = fillRequestRepository
                .findByStatus(FillRequestStatusEnum.QUEUED);

            if (queuedRequests.isEmpty()) {
                log.debug("No queued requests to update priorities");
                return;
            }

            int updatedCount = 0;
            for (FillRequest request : queuedRequests) {
                int newPriority = priorityCalculationService.calculateAgeBasedPriority(request.getCreatedAt());
                
                // Only update if priority has changed
                if (newPriority != request.getPriority()) {
                    int oldPriority = request.getPriority();
                    request.setPriority(newPriority);
                    fillRequestRepository.save(request);
                    
                    log.info("Updated priority for request {}: {} -> {} ({})", 
                        request.getId(), oldPriority, newPriority, 
                        priorityCalculationService.getPriorityLevelDescription(newPriority));
                    
                    updatedCount++;
                }
            }

            if (updatedCount > 0) {
                log.info("Updated priorities for {} queued requests", updatedCount);
            }

        } catch (Exception e) {
            log.error("Error updating queued request priorities", e);
        }
    }

    /**
     * Update priority for a specific request
     */
    @Transactional
    public void updateRequestPriority(FillRequest request) {
        try {
            int newPriority = priorityCalculationService.calculateAgeBasedPriority(request.getCreatedAt());
            
            if (newPriority != request.getPriority()) {
                int oldPriority = request.getPriority();
                request.setPriority(newPriority);
                fillRequestRepository.save(request);
                
                log.info("Updated priority for request {}: {} -> {} ({})", 
                    request.getId(), oldPriority, newPriority,
                    priorityCalculationService.getPriorityLevelDescription(newPriority));
            }
        } catch (Exception e) {
            log.error("Error updating priority for request {}", request.getId(), e);
        }
    }

    /**
     * Get priority statistics for monitoring
     */
    public PriorityStatistics getPriorityStatistics() {
        List<FillRequest> queuedRequests = fillRequestRepository
            .findByStatus(FillRequestStatusEnum.QUEUED);

        PriorityStatistics stats = new PriorityStatistics();
        
        for (FillRequest request : queuedRequests) {
            int priority = request.getPriority();
            
            if (priority >= 15) stats.criticalCount++;
            else if (priority >= 10) stats.highCount++;
            else if (priority >= 5) stats.mediumCount++;
            else if (priority >= 1) stats.lowCount++;
            else stats.defaultCount++;
        }

        stats.totalQueued = queuedRequests.size();
        stats.timestamp = LocalDateTime.now();
        
        return stats;
    }

    /**
     * Priority statistics for monitoring
     */
    public static class PriorityStatistics {
        public int criticalCount = 0;
        public int highCount = 0;
        public int mediumCount = 0;
        public int lowCount = 0;
        public int defaultCount = 0;
        public int totalQueued = 0;
        public LocalDateTime timestamp;
    }
}
