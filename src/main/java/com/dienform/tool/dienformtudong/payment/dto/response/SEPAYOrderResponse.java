package com.dienform.tool.dienformtudong.payment.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SEPAYOrderResponse {

  private boolean success;
  private String orderId;
  private String qrCodeUrl;
  private BigDecimal amount;
  private LocalDateTime expiresAt;
  private String message;
}
