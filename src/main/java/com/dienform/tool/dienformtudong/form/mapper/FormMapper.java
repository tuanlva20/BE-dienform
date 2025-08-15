package com.dienform.tool.dienformtudong.form.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.form.dto.request.FormRequest;
import com.dienform.tool.dienformtudong.form.dto.response.FormDetailResponse;
import com.dienform.tool.dienformtudong.form.dto.response.FormResponse;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.formstatistic.dto.response.FormStatisticResponse;
import com.dienform.tool.dienformtudong.formstatistic.entity.FormStatistic;
import com.dienform.tool.dienformtudong.question.dto.response.QuestionOptionResponse;
import com.dienform.tool.dienformtudong.question.dto.response.QuestionResponse;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FormMapper {
  Form toEntity(FormRequest formRequest);

  FormRequest toDto(Form form);

  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  Form partialUpdate(FormRequest formRequest, @MappingTarget Form form);

  FormResponse toResponse(Form form);

  FormDetailResponse toDetailResponse(Form form);

  @Mapping(target = "status",
      expression = "java(fillRequest.getStatus() != null ? fillRequest.getStatus().name() : null)")
  FillRequestResponse fillRequestToFillRequestResponse(FillRequest fillRequest);

  // Support methods for nested mappings
  QuestionResponse toQuestionResponse(Question question);

  QuestionOptionResponse toQuestionOptionResponse(QuestionOption option);

  FormStatisticResponse toFormStatisticResponse(FormStatistic formStatistic);
}
