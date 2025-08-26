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
public class PaymentSuccessEvent {
  private String orderId;
  private String userId;
  private BigDecimal amount;
  private BigDecimal actualAmount;
  private LocalDateTime processedAt;
  private String message;
}
