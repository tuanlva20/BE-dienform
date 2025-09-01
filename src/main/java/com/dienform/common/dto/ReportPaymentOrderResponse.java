package com.dienform.common.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.dienform.common.entity.ReportPaymentOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportPaymentOrderResponse {

  public static ReportPaymentOrderResponse fromEntity(ReportPaymentOrder paymentOrder) {
    return ReportPaymentOrderResponse.builder().id(paymentOrder.getId())
        .userId(paymentOrder.getUser().getId()).userName(paymentOrder.getUser().getName())
        .userEmail(paymentOrder.getUser().getEmail()).userAvatar(paymentOrder.getUser().getAvatar())
        .amount(paymentOrder.getAmount()).paymentType(paymentOrder.getPaymentType().name())
        .paymentTypeDisplayName(paymentOrder.getPaymentType().getDisplayName())
        .status(paymentOrder.getStatus().name())
        .statusDisplayName(paymentOrder.getStatus().getDisplayName())
        .description(paymentOrder.getDescription()).transactionId(paymentOrder.getTransactionId())
        .orderId(paymentOrder.getTransactionId()) // Use transactionId as orderId for bank transfer
                                                  // reference
        .isPromotional(paymentOrder.getIsPromotional()).isReported(paymentOrder.getIsReported())
        .createdAt(paymentOrder.getCreatedAt()).updatedAt(paymentOrder.getUpdatedAt()).build();
  }

  private UUID id;
  private UUID userId;
  private String userName;
  private String userEmail;
  private String userAvatar;
  private BigDecimal amount;
  private String paymentType;
  private String paymentTypeDisplayName;
  private String status;
  private String statusDisplayName;
  private String description;
  private String transactionId;
  private String orderId; // Added orderId field
  private Boolean isPromotional;
  private Boolean isReported;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
