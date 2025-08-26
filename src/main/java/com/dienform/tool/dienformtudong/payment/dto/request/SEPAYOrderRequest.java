package com.dienform.tool.dienformtudong.payment.dto.request;

import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SEPAYOrderRequest {

  @NotNull(message = "Số tiền không được để trống")
  @DecimalMin(value = "1000", message = "Số tiền tối thiểu là 1,000 VNĐ")
  private BigDecimal amount;

  private String description;
}
