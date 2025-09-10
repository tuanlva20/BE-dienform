package com.dienform.tool.dienformtudong.datamapping.dto.request;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import com.dienform.common.util.Iso8601LocalDateTimeDeserializer;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DataFillRequestDTO {
  private String formName;

  @NotBlank(message = "Form ID is required")
  private String formId;

  @NotBlank(message = "Sheet link is required")
  private String sheetLink;

  @NotNull(message = "Mappings are required")
  @Valid
  private List<ColumnMapping> mappings;

  @Min(value = 1, message = "Submission count must be at least 1")
  private Integer submissionCount;

  @DecimalMin(value = "0.0", message = "Price must be non-negative")
  private BigDecimal pricePerSurvey;

  @JsonAlias({"humanLike", "isHumanLike"})
  @JsonProperty("isHumanLike")
  private Boolean isHumanLike = false;

  @JsonDeserialize(using = Iso8601LocalDateTimeDeserializer.class)
  private LocalDateTime startDate;

  @JsonDeserialize(using = Iso8601LocalDateTimeDeserializer.class)
  @NotNull(message = "endDate is required")
  private LocalDateTime endDate;
}
