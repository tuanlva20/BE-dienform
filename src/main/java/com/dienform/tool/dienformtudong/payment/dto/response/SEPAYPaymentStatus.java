package com.dienform.tool.dienformtudong.payment.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.dienform.tool.dienformtudong.payment.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SEPAYPaymentStatus {

  private String orderId;
  private PaymentStatus status;
  private BigDecimal amount;
  private BigDecimal actualAmount;
  private LocalDateTime processedAt;
  private String message;
  private boolean success;
}
