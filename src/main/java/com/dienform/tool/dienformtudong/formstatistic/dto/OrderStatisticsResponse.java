package com.dienform.tool.dienformtudong.formstatistic.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.dienform.tool.dienformtudong.payment.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatisticsResponse {

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class OrderInfo {
    private UUID id;
    private String orderId;
    private BigDecimal amount;
    private LocalDateTime createdAt;
    private PaymentStatus status;
  }

  private List<OrderInfo> orders;
  private Long totalOrders;
  private BigDecimal totalAmount;
  private BigDecimal totalCompletedAmount;
  private Long pendingOrders;
  private Long completedOrders;
  private Long failedOrders;

  private Long expiredOrders;
  // Pagination metadata
  private Integer pageSize;
  private Integer pageNumber;
  private Integer totalPages;

  private Long totalElements;
}
