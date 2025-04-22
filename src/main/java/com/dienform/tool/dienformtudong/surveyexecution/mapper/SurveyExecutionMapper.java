package com.dienform.tool.dienformtudong.surveyexecution.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import com.dienform.tool.dienformtudong.surveyexecution.dto.response.SurveyExecutionResponse;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SurveyExecution;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SurveyExecutionMapper {
  SurveyExecutionResponse toResponse(SurveyExecution entity);
}
