package com.dienform.common.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialReportResponse {
  private BigDecimal totalDeposited;
  private BigDecimal totalSpent;
  private BigDecimal totalPromotional;
  private String currency = "VND";
}
