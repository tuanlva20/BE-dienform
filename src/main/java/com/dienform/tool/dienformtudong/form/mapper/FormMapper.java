package com.dienform.tool.dienformtudong.form.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import com.dienform.tool.dienformtudong.form.dto.request.FormRequest;
import com.dienform.tool.dienformtudong.form.dto.response.FormResponse;
import com.dienform.tool.dienformtudong.form.entity.Form;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FormMapper {
  Form toEntity(FormRequest request);

  FormRequest toRequest(Form entity);

  FormResponse toResponse(Form entity);

  Form toEntity(FormResponse response);
}
