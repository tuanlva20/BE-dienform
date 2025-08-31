package com.dienform.tool.dienformtudong.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentOverpaymentEvent {
  private String orderId;
  private String userId;
  private BigDecimal expectedAmount;
  private BigDecimal actualAmount;
  private BigDecimal excessAmount;
  private LocalDateTime processedAt;
  private String securityRiskLevel;
  private String message;
}
