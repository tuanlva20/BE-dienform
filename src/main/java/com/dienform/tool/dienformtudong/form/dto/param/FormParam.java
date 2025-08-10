package com.dienform.tool.dienformtudong.form.dto.param;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class FormParam {
  private String search;
  private String createdBy; // UUID string of creator; optional

  @Builder.Default
  private int page = 0;

  @Builder.Default
  private int size = 10;
}
