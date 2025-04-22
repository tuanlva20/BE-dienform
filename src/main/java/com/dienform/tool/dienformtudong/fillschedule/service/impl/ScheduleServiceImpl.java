package com.dienform.tool.dienformtudong.fillschedule.service.impl;

import com.dienform.common.exception.BadRequestException;
import com.dienform.common.exception.ResourceNotFoundException;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.fillschedule.dto.request.ScheduleDTO;
import com.dienform.tool.dienformtudong.fillschedule.dto.response.ScheduleResponse;
import com.dienform.tool.dienformtudong.fillschedule.entity.FillSchedule;
import com.dienform.tool.dienformtudong.fillschedule.repository.FillScheduleRepository;
import com.dienform.tool.dienformtudong.fillschedule.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {

    private final FillScheduleRepository scheduleRepository;
    private final FillRequestRepository fillRequestRepository;

    @Override
    @Transactional
    public ScheduleResponse createSchedule(UUID requestId, ScheduleDTO scheduleDTO) {
        // Validate fill request exists
        if (!fillRequestRepository.existsById(requestId)) {
            throw new ResourceNotFoundException("Fill Request", "id", requestId);
        }
        
        // Check if schedule already exists
        if (scheduleRepository.findByFillRequestId(requestId).isPresent()) {
            throw new ResourceNotFoundException("Schedule", "fillRequestId", requestId);
        }
        
        // Validate schedule data
        validateScheduleData(scheduleDTO);
        
        // Create schedule
        FillSchedule schedule = FillSchedule.builder()
//                .fillRequestId(requestId)
                .startDate(scheduleDTO.getStartDate())
                .build();
        
        FillSchedule savedSchedule = scheduleRepository.save(schedule);
        
        return mapToScheduleResponse(savedSchedule);
    }

    @Override
    public ScheduleResponse getScheduleByRequestId(UUID requestId) {
        // Validate fill request exists
        if (!fillRequestRepository.existsById(requestId)) {
            throw new ResourceNotFoundException("Fill Request", "id", requestId);
        }
        
        FillSchedule schedule = scheduleRepository.findByFillRequestId(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", "fillRequestId", requestId));
        
        return mapToScheduleResponse(schedule);
    }

    @Override
    @Transactional
    public ScheduleResponse updateSchedule(UUID requestId, ScheduleDTO scheduleDTO) {
        // Validate fill request exists
        if (!fillRequestRepository.existsById(requestId)) {
            throw new ResourceNotFoundException("Fill Request", "id", requestId);
        }
        
        // Find schedule
        FillSchedule schedule = scheduleRepository.findByFillRequestId(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", "fillRequestId", requestId));
        
        // Validate schedule data
        validateScheduleData(scheduleDTO);
        
        // Update schedule
        schedule.setStartDate(scheduleDTO.getStartDate());
        // schedule.setEndDate(scheduleDTO.getEndDate());
        // schedule.setExecutionsPerDay(scheduleDTO.getExecutionsPerDay());
        // schedule.setCustomSchedule(scheduleDTO.getCustomSchedule());
        
        FillSchedule updatedSchedule = scheduleRepository.save(schedule);
        
        return mapToScheduleResponse(updatedSchedule);
    }

    @Override
    @Transactional
    public void deleteSchedule(UUID requestId) {
        // Validate fill request exists
        if (!fillRequestRepository.existsById(requestId)) {
            throw new ResourceNotFoundException("Fill Request", "id", requestId);
        }
        
        // Find and delete schedule
        FillSchedule schedule = scheduleRepository.findByFillRequestId(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", "fillRequestId", requestId));
        
        scheduleRepository.delete(schedule);
    }
    
    private void validateScheduleData(ScheduleDTO scheduleDTO) {
        if (scheduleDTO.getStartDate() == null) {
            throw new BadRequestException("Start date is required");
        }
        
        // if (scheduleDTO.getEndDate() == null) {
        //     throw new BadRequestException("End date is required");
        // }
        
        // if (scheduleDTO.getStartDate().isAfter(scheduleDTO.getEndDate())) {
        //     throw new BadRequestException("Start date must be before end date");
        // }
        
        // if (scheduleDTO.getExecutionsPerDay() <= 0) {
        //     throw new BadRequestException("Executions per day must be greater than 0");
        // }
    }
    
    private ScheduleResponse mapToScheduleResponse(FillSchedule schedule) {
        return ScheduleResponse.builder()
                .id(schedule.getId())
//                .fillRequestId(schedule.getFillRequestId())
                .startDate(schedule.getStartDate())
                // .endDate(schedule.getEndDate())
                // .executionsPerDay(schedule.getExecutionsPerDay())
                // .customSchedule(schedule.getCustomSchedule())
                // .active(schedule.isActive())
                .createdAt(schedule.getCreatedAt())
                .updatedAt(schedule.getUpdatedAt())
                .build();
    }
}
