package com.dienform.tool.dienformtudong.payment.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.payment.entity.PaymentOrder;
import com.dienform.tool.dienformtudong.payment.enums.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverpaymentOrderService {

  /**
   * DTO for overpayment details
   */
  @lombok.Data
  @lombok.Builder
  @lombok.NoArgsConstructor
  @lombok.AllArgsConstructor
  public static class OverpaymentDetails {
    private String orderId;
    private String userId;
    private BigDecimal expectedAmount;
    private BigDecimal actualAmount;
    private BigDecimal excessAmount;
    private LocalDateTime processedAt;
    private String securityRiskLevel;
  }

  private final PaymentRealtimeService paymentRealtimeService;
  private final com.dienform.tool.dienformtudong.payment.repository.PaymentOrderRepository paymentOrderRepository;
  private final com.dienform.common.repository.ReportPaymentOrderRepository reportPaymentOrderRepository;
  private final com.dienform.common.repository.UserRepository userRepository;

  /**
   * Process overpayment order - DO NOT add to user balance for security reasons This prevents
   * potential API abuse/attacks
   */
  @Transactional
  public void processOverpaymentOrder(PaymentOrder order, BigDecimal actualAmount) {
    try {
      log.warn(
          "Processing OVERPAYMENT order: {} - Expected: {}, Actual: {}. NOT adding to balance due to security risk!",
          order.getOrderId(), order.getAmount(), actualAmount);

      // Update order with actual amount and overpayment status
      order.setActualAmount(actualAmount);
      order.setStatus(PaymentStatus.OVERPAYMENT);
      order.setProcessedAt(LocalDateTime.now());
      paymentOrderRepository.save(order);

      // DO NOT add actual amount to user balance for security reasons
      // This prevents potential API abuse/attacks

      // Emit realtime event for overpayment (security alert)
      paymentRealtimeService.emitOverpaymentEvent(order);

      log.warn("OVERPAYMENT order processed: {} - Amount {} NOT added to balance for security",
          order.getOrderId(), actualAmount);

      // Sync to report table (mark as PROCESSING with actual amount)
      syncReportRecord(order);

    } catch (Exception e) {
      log.error("Error processing overpayment order: {}", order.getOrderId(), e);
      throw new RuntimeException("Failed to process overpayment order", e);
    }
  }

  /**
   * Get overpayment details for reporting
   */
  public OverpaymentDetails getOverpaymentDetails(PaymentOrder order) {
    BigDecimal expectedAmount = order.getAmount();
    BigDecimal actualAmount = order.getActualAmount();
    BigDecimal excessAmount = actualAmount.subtract(expectedAmount);

    // Determine security risk level based on excess amount
    String securityRiskLevel = determineSecurityRiskLevel(excessAmount, expectedAmount);

    return OverpaymentDetails.builder().orderId(order.getOrderId()).userId(order.getUserId())
        .expectedAmount(expectedAmount).actualAmount(actualAmount).excessAmount(excessAmount)
        .processedAt(order.getProcessedAt()).securityRiskLevel(securityRiskLevel).build();
  }

  private void syncReportRecord(PaymentOrder order) {
    try {
      java.util.Optional<com.dienform.common.entity.ReportPaymentOrder> reportOpt =
          reportPaymentOrderRepository.findByTransactionId(order.getOrderId());
      com.dienform.common.entity.ReportPaymentOrder report;
      if (reportOpt.isPresent()) {
        report = reportOpt.get();
      } else {
        com.dienform.common.entity.User user = null;
        try {
          user = userRepository.findById(java.util.UUID.fromString(order.getUserId())).orElse(null);
        } catch (Exception ignore) {
        }
        report = com.dienform.common.entity.ReportPaymentOrder.builder().user(user)
            .transactionId(order.getOrderId())
            .paymentType(com.dienform.common.entity.ReportPaymentOrder.PaymentType.DEPOSIT)
            .isPromotional(false).isReported(false).build();
      }
      java.math.BigDecimal amount =
          order.getActualAmount() != null ? order.getActualAmount() : order.getAmount();
      report.setAmount(amount);
      report.setStatus(com.dienform.common.entity.ReportPaymentOrder.PaymentStatus.CANCELLED);
      reportPaymentOrderRepository.save(report);
    } catch (Exception e) {
      log.error("Error syncing report record for overpayment order: {}", order.getOrderId(), e);
    }
  }

  /**
   * Determine security risk level based on excess amount
   */
  private String determineSecurityRiskLevel(BigDecimal excessAmount, BigDecimal expectedAmount) {
    if (excessAmount.compareTo(BigDecimal.ZERO) <= 0) {
      return "NONE";
    }

    // Calculate percentage of excess
    BigDecimal percentage = excessAmount.divide(expectedAmount, 4, BigDecimal.ROUND_HALF_UP)
        .multiply(new BigDecimal("100"));

    if (percentage.compareTo(new BigDecimal("10")) <= 0) {
      return "LOW"; // Excess <= 10%
    } else if (percentage.compareTo(new BigDecimal("50")) <= 0) {
      return "MEDIUM"; // Excess 10-50%
    } else {
      return "HIGH"; // Excess > 50%
    }
  }
}
