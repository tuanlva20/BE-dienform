package com.dienform.tool.dienformtudong.fillrequest.mapper;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface FillRequestMapper {

  @Mapping(source = "humanLike", target = "humanLike")
  @Mapping(target = "answerDistributions", source = "answerDistributions",
      qualifiedByName = "mapAnswerDistributions")
  FillRequestResponse toReponse(FillRequest fillRequest);

  FillRequest toEntity(FillRequestResponse fillRequestResponse);

  @Named("mapAnswerDistributions")
  default List<FillRequestResponse.AnswerDistributionResponse> mapAnswerDistributions(
      List<AnswerDistribution> distributions) {
    if (distributions == null) {
      return null;
    }

    return distributions.stream().map(distribution -> {
      FillRequestResponse.AnswerDistributionResponse.AnswerDistributionResponseBuilder builder =
          FillRequestResponse.AnswerDistributionResponse.builder()
              .questionId(distribution.getQuestion().getId())
              .percentage(distribution.getPercentage())
              .count(distribution.getCount() != null ? distribution.getCount() : 0) // Handle null
                                                                                    // count
              .valueString(distribution.getValueString()).rowId(distribution.getRowId())
              .positionIndex(distribution.getPositionIndex());

      // Add optionId and option info if available
      if (distribution.getOption() != null) {
        builder.optionId(distribution.getOption().getId())
            .option(FillRequestResponse.AnswerDistributionResponse.OptionInfo.builder()
                .id(distribution.getOption().getId()).text(distribution.getOption().getText())
                .build());
      } else {
        // Set optionId to null explicitly for text questions
        builder.optionId(null);
      }

      return builder.build();
    }).toList();
  }
}
