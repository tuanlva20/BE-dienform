package com.dienform.tool.mahoa.dto.request;

import java.util.UUID;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EncodeDataRequest {
  private UUID formId;

  private String formLink;

  @NotBlank(message = "sheetLink is required")
  private String sheetLink;

  @AssertTrue(message = "Exactly one of formId or formLink must be provided")
  public boolean isFormIdentifierValid() {
    boolean hasId = formId != null;
    boolean hasLink = formLink != null && !formLink.trim().isEmpty();
    return hasId ^ hasLink;
  }
}


