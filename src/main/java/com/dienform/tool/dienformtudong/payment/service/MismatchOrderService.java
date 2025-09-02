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
public class MismatchOrderService {

  /**
   * DTO for mismatch details
   */
  @lombok.Data
  @lombok.Builder
  @lombok.NoArgsConstructor
  @lombok.AllArgsConstructor
  public static class MismatchDetails {
    private String orderId;
    private String userId;
    private BigDecimal expectedAmount;
    private BigDecimal actualAmount;
    private BigDecimal difference;
    private boolean isOverpayment;
    private boolean isUnderpayment;
    private LocalDateTime processedAt;
  }

  private final UserBalanceService userBalanceService;
  private final PaymentRealtimeService paymentRealtimeService;

  private final com.dienform.tool.dienformtudong.payment.repository.PaymentOrderRepository paymentOrderRepository;
  private final com.dienform.common.repository.ReportPaymentOrderRepository reportPaymentOrderRepository;
  private final com.dienform.common.repository.UserRepository userRepository;

  /**
   * Process underpayment order by adding actual amount to user balance This ensures user receives
   * the money they actually transferred Note: This only handles underpayment (actual < expected),
   * not overpayment
   */
  @Transactional
  public void processMismatchOrder(PaymentOrder order, BigDecimal actualAmount) {
    try {
      log.info("Processing underpayment order: {} - Expected: {}, Actual: {}", order.getOrderId(),
          order.getAmount(), actualAmount);

      // Update order with actual amount and mismatch status
      order.setActualAmount(actualAmount);
      order.setStatus(PaymentStatus.MISMATCH);
      order.setProcessedAt(LocalDateTime.now());
      paymentOrderRepository.save(order);

      // Add actual amount to user balance (even though it's a mismatch)
      userBalanceService.addBalance(order.getUserId(), actualAmount);

      // Emit realtime event for mismatch
      paymentRealtimeService.emitMismatchPaymentEvent(order);

      // Sync to report table (mark as PROCESSING with actual amount)
      syncReportRecord(order);

      log.info("Underpayment order processed successfully: {} - Added {} to user balance",
          order.getOrderId(), actualAmount);

    } catch (Exception e) {
      log.error("Error processing underpayment order: {}", order.getOrderId(), e);
      throw new RuntimeException("Failed to process underpayment order", e);
    }
  }

  /**
   * Get underpayment details for reporting
   */
  public MismatchDetails getMismatchDetails(PaymentOrder order) {
    BigDecimal expectedAmount = order.getAmount();
    BigDecimal actualAmount = order.getActualAmount();
    BigDecimal difference = actualAmount.subtract(expectedAmount);

    return MismatchDetails.builder().orderId(order.getOrderId()).userId(order.getUserId())
        .expectedAmount(expectedAmount).actualAmount(actualAmount).difference(difference)
        .isOverpayment(difference.compareTo(BigDecimal.ZERO) > 0)
        .isUnderpayment(difference.compareTo(BigDecimal.ZERO) < 0)
        .processedAt(order.getProcessedAt()).build();
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
      report.setStatus(com.dienform.common.entity.ReportPaymentOrder.PaymentStatus.COMPLETED);
      reportPaymentOrderRepository.save(report);
    } catch (Exception e) {
      log.error("Error syncing report record for mismatch order: {}", order.getOrderId(), e);
    }
  }
}

