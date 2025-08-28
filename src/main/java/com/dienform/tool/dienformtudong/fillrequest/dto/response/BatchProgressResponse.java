package com.dienform.tool.dienformtudong.fillrequest.dto.response;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchProgressResponse {
  private int currentBatch;
  private int totalBatches;
  private int batchSize;
  private LocalDateTime estimatedCompletionDate;
  private boolean adjustedForTimeConstraint;
  private boolean batchProcessingEnabled;
  private String status;
  private String message;
}
