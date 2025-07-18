package com.dienform.tool.dienformtudong.fillrequest.mapper;

import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface FillRequestMapper {
  FillRequestResponse toReponse(FillRequest fillRequest);
  FillRequest toEntity(FillRequestResponse fillRequestResponse);
}
