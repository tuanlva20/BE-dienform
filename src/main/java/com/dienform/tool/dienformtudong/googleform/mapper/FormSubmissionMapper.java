package com.dienform.tool.dienformtudong.googleform.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import com.dienform.tool.dienformtudong.googleform.dto.FormSubmissionRequest;
import com.dienform.tool.dienformtudong.googleform.entity.FormSubmission;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FormSubmissionMapper {
  FormSubmission toEntity(FormSubmissionRequest request);
  // FormSubmissionResponse toResponse(FormSubmission entity);
}
