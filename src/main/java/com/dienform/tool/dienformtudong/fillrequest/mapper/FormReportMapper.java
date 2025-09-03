package com.dienform.tool.dienformtudong.fillrequest.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;
import com.dienform.tool.dienformtudong.fillrequest.dto.FormReportResponse;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;

@Component
@Mapper(componentModel = "spring")
public abstract class FormReportMapper {

  @Mapping(source = "form.name", target = "formName")
  @Mapping(source = "form.editLink", target = "formUrl")
  @Mapping(source = "form.status", target = "formStatus")
  @Mapping(source = "form.status", target = "formStatusDisplayName")
  @Mapping(source = "humanLike", target = "formType")
  @Mapping(source = "status", target = "statusDisplayName")
  @Mapping(source = "id", target = "completionProgress")
  @Mapping(source = "totalPrice", target = "totalCost")
  @Mapping(source = "pricePerSurvey", target = "costPerSurvey")
  public abstract FormReportResponse toFormReportResponse(FillRequest fillRequest);

  @Named("mapFormType")
  protected String mapFormType(boolean humanLike) {
    return humanLike ? "Manual" : "Auto";
  }

  @Named("mapStatusDisplayName")
  protected String mapStatusDisplayName(FillRequestStatusEnum status) {
    if (status == null)
      return "";

    return switch (status) {
      case QUEUED -> "Đang chờ";
      case IN_PROCESS -> "Đang xử lý";
      case COMPLETED -> "Hoàn thành";
      case FAILED -> "Thất bại";
    };
  }

  @Named("mapFormStatusDisplayName")
  protected String mapFormStatusDisplayName(
      com.dienform.tool.dienformtudong.form.enums.FormStatusEnum formStatus) {
    if (formStatus == null)
      return "";

    return switch (formStatus) {
      case CREATED -> "Đã tạo";
      case PROCESSING -> "Đang xử lý";
      case COMPLETED -> "Hoàn thành";
    };
  }

  @Named("mapCompletionProgress")
  protected String mapCompletionProgress(FillRequest fillRequest) {
    if (fillRequest.getSurveyCount() == 0)
      return "0/0";
    return fillRequest.getCompletedSurvey() + "/" + fillRequest.getSurveyCount();
  }
}
