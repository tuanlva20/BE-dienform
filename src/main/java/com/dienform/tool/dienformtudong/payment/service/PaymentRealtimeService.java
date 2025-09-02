package com.dienform.tool.dienformtudong.payment.service;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import com.dienform.realtime.PaymentRealtimeGateway;
import com.dienform.tool.dienformtudong.payment.dto.PaymentMismatchEvent;
import com.dienform.tool.dienformtudong.payment.dto.PaymentOverpaymentEvent;
import com.dienform.tool.dienformtudong.payment.dto.PaymentSuccessEvent;
import com.dienform.tool.dienformtudong.payment.entity.PaymentOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentRealtimeService {

  /**
   * Event class for balance updates
   */
  public static class BalanceUpdateEvent {
    private final String userId;
    private final BigDecimal balance;
    private final String timestamp;

    public BalanceUpdateEvent(String userId, BigDecimal balance) {
      this.userId = userId;
      this.balance = balance;
      this.timestamp = java.time.Instant.now().toString();
    }

    public String getUserId() {
      return userId;
    }

    public BigDecimal getBalance() {
      return balance;
    }

    public String getTimestamp() {
      return timestamp;
    }
  }

  private final PaymentRealtimeGateway paymentRealtimeGateway;

  private final UserBalanceService userBalanceService;

  /**
   * Emit payment success event with balance update This method is called after payment processing
   */
  public void emitPaymentSuccessEvent(PaymentOrder order) {
    try {
      // Get updated balance after payment
      BigDecimal newBalance = userBalanceService.getBalance(order.getUserId());

      // Emit balance update via socket
      paymentRealtimeGateway.emitBalanceUpdate(order.getUserId(), newBalance);

      // Also emit payment success event
      PaymentSuccessEvent event = PaymentSuccessEvent.builder().orderId(order.getOrderId())
          .userId(order.getUserId()).amount(order.getAmount()).actualAmount(order.getActualAmount())
          .processedAt(order.getProcessedAt()).message("Thanh toán thành công").build();

      log.info("Payment success event emitted for user: {} with new balance: {}", order.getUserId(),
          newBalance);
    } catch (Exception e) {
      log.error("Error emitting payment success event for user: {}", order.getUserId(), e);
      // Don't throw exception to avoid affecting payment flow
    }
  }

  /**
   * Emit balance update for any reason (not just payment success) This method is called after
   * balance changes (deduction, addition, etc.)
   */
  public void emitBalanceUpdate(String userId) {
    try {
      BigDecimal currentBalance = userBalanceService.getBalance(userId);
      paymentRealtimeGateway.emitBalanceUpdate(userId, currentBalance);
      log.info("Balance update emitted for user: {} with balance: {}", userId, currentBalance);
    } catch (Exception e) {
      log.error("Error emitting balance update for user: {}", userId, e);
      // Don't throw exception to avoid affecting balance operations
    }
  }

  /**
   * Emit balance update with specific balance amount This method is used when we already have the
   * balance value
   */
  public void emitBalanceUpdate(String userId, BigDecimal balance) {
    try {
      paymentRealtimeGateway.emitBalanceUpdate(userId, balance);
      log.info("Balance update emitted for user: {} with balance: {}", userId, balance);
    } catch (Exception e) {
      log.error("Error emitting balance update for user: {} with balance: {}", userId, balance, e);
      // Don't throw exception to avoid affecting balance operations
    }
  }

  /**
   * Emit mismatch payment event (for underpayment)
   */
  public void emitMismatchPaymentEvent(PaymentOrder order) {
    try {
      // Get updated balance after mismatch payment
      BigDecimal newBalance = userBalanceService.getBalance(order.getUserId());

      // Emit balance update via socket
      paymentRealtimeGateway.emitBalanceUpdate(order.getUserId(), newBalance);

      // Create mismatch payment event
      PaymentMismatchEvent event =
          PaymentMismatchEvent.builder().orderId(order.getOrderId()).userId(order.getUserId())
              .expectedAmount(order.getAmount()).actualAmount(order.getActualAmount())
              .difference(order.getActualAmount().subtract(order.getAmount()))
              .processedAt(order.getProcessedAt())
              .message("Thanh toán với số tiền thiếu - đã cộng số tiền thực tế").build();

      log.info("Underpayment event emitted for user: {} with new balance: {}", order.getUserId(),
          newBalance);
    } catch (Exception e) {
      log.error("Error emitting underpayment event for user: {}", order.getUserId(), e);
      // Don't throw exception to avoid affecting payment flow
    }
  }

  /**
   * Emit overpayment event (security alert)
   */
  public void emitOverpaymentEvent(PaymentOrder order) {
    try {
      // Calculate excess amount
      BigDecimal excessAmount = order.getActualAmount().subtract(order.getAmount());

      // Determine security risk level
      String securityRiskLevel = determineSecurityRiskLevel(excessAmount, order.getAmount());

      // Create overpayment event
      PaymentOverpaymentEvent event = PaymentOverpaymentEvent.builder().orderId(order.getOrderId())
          .userId(order.getUserId()).expectedAmount(order.getAmount())
          .actualAmount(order.getActualAmount()).excessAmount(excessAmount)
          .processedAt(order.getProcessedAt()).securityRiskLevel(securityRiskLevel)
          .message("Phát hiện thanh toán vượt quá - không cộng vào balance vì lý do bảo mật")
          .build();

      log.warn("Overpayment event emitted for user: {} - Excess: {}, Risk Level: {}",
          order.getUserId(), excessAmount, securityRiskLevel);
    } catch (Exception e) {
      log.error("Error emitting overpayment event for user: {}", order.getUserId(), e);
      // Don't throw exception to avoid affecting payment flow
    }
  }

  /**
   * Transactional event listener for balance updates This ensures balance updates are emitted only
   * after successful transaction commit
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleBalanceUpdateEvent(BalanceUpdateEvent balanceUpdateEvent) {
    try {
      log.debug("Processing balance update event for user: {}", balanceUpdateEvent.getUserId());
      paymentRealtimeGateway.emitBalanceUpdate(balanceUpdateEvent.getUserId(),
          balanceUpdateEvent.getBalance());
      log.info("Balance update event processed successfully for user: {}",
          balanceUpdateEvent.getUserId());
    } catch (Exception e) {
      log.error("Error processing balance update event for user: {}",
          balanceUpdateEvent.getUserId(), e);
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
