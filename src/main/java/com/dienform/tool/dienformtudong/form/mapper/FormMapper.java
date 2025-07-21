package com.dienform.tool.dienformtudong.form.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;
import com.dienform.tool.dienformtudong.form.dto.request.FormRequest;
import com.dienform.tool.dienformtudong.form.dto.response.FormDetailResponse;
import com.dienform.tool.dienformtudong.form.dto.response.FormResponse;
import com.dienform.tool.dienformtudong.form.entity.Form;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FormMapper {
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  Form toEntity(FormRequest formRequest);

  FormRequest toDto(Form form);

  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  Form partialUpdate(FormRequest formRequest, @MappingTarget Form form);

  FormResponse toResponse(Form form);

  FormDetailResponse toDetailResponse(Form form);

  // FillRequestResponse fillRequestToFillRequestResponse(FillRequest fillRequest);
}
