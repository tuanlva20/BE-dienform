package com.dienform.tool.dienformtudong.payment.dto.request;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SEPAYWebhookRequest {

  private String orderId;
  private BigDecimal amount;
  private BigDecimal actualAmount;
  private String status; // "success" | "failed"
  private String signature;
  private String timestamp;
  private String transactionId;
  private String message;
}
