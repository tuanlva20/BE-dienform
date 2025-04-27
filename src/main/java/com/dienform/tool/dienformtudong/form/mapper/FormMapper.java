package com.dienform.tool.dienformtudong.form.mapper;

import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.form.dto.response.FormDetailResponse;
import com.dienform.tool.dienformtudong.form.dto.response.FormResponse;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.dto.request.FormRequest;
import org.mapstruct.*;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface FormMapper {
  Form toEntity(FormRequest formRequest);

  FormRequest toDto(Form form);

  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  Form partialUpdate(FormRequest formRequest, @MappingTarget Form form);

  FormResponse toResponse(Form form);

  FormDetailResponse toDetailResponse(Form form);

//  FillRequestResponse fillRequestToFillRequestResponse(FillRequest fillRequest);
}
