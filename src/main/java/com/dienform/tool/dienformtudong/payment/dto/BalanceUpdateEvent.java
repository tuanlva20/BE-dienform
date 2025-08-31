package com.dienform.tool.dienformtudong.payment.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceUpdateEvent {
  private String userId;
  private BigDecimal balance;
  private String updatedAt;
}
