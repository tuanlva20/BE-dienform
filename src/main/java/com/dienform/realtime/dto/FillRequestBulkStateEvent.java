package com.dienform.realtime.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillRequestBulkStateEvent {
  private String formId;
  private List<FillRequestUpdateEvent> requests;
  private String updatedAt;
}



