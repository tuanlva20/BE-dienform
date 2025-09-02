package com.dienform.tool.dienformtudong.payment.service;

import java.math.BigDecimal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.payment.entity.UserBalance;
import com.dienform.tool.dienformtudong.payment.enums.PaymentStatus;
import com.dienform.tool.dienformtudong.payment.exception.PaymentException;
import com.dienform.tool.dienformtudong.payment.repository.UserBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserBalanceService {

  /**
   * Balance information DTO
   */
  @lombok.Data
  @lombok.Builder
  @lombok.NoArgsConstructor
  @lombok.AllArgsConstructor
  public static class BalanceInfo {
    private String userId;
    private BigDecimal balance;
    private BigDecimal totalDeposited;
    private BigDecimal totalSpent;
  }

  private final UserBalanceRepository userBalanceRepository;
  private final com.dienform.tool.dienformtudong.payment.repository.PaymentOrderRepository paymentOrderRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public void addBalance(String userId, BigDecimal amount) {
    try {
      UserBalance userBalance = userBalanceRepository.findByUserId(userId)
          .orElseGet(() -> UserBalance.builder().userId(userId).balance(BigDecimal.ZERO)
              .totalDeposited(BigDecimal.ZERO).totalSpent(BigDecimal.ZERO).build());

      BigDecimal currentBalance = userBalance.getBalance();
      BigDecimal newBalance = currentBalance.add(amount);

      userBalance.setBalance(newBalance);
      userBalance.setTotalDeposited(userBalance.getTotalDeposited().add(amount));
      userBalanceRepository.save(userBalance);

      log.info("Added balance {} to user {}. New balance: {}", amount, userId, newBalance);

      // Publish balance update event for realtime notification
      publishBalanceUpdateEvent(userId, newBalance);

    } catch (Exception e) {
      log.error("Error adding balance to user: {}", userId, e);
      throw new PaymentException("Failed to add balance", e);
    }
  }

  /**
   * Deduct balance from user account. Throws PaymentException if insufficient funds.
   */
  @Transactional
  public void deductBalance(String userId, BigDecimal amount) {
    try {
      if (amount == null || amount.signum() <= 0) {
        throw new PaymentException("Invalid amount to deduct");
      }

      UserBalance userBalance = userBalanceRepository.findByUserId(userId)
          .orElseGet(() -> UserBalance.builder().userId(userId).balance(BigDecimal.ZERO)
              .totalDeposited(BigDecimal.ZERO).totalSpent(BigDecimal.ZERO).build());

      BigDecimal currentBalance = userBalance.getBalance();
      if (currentBalance.compareTo(amount) < 0) {
        throw new PaymentException("Số dư không đủ");
      }

      BigDecimal newBalance = currentBalance.subtract(amount);
      userBalance.setBalance(newBalance);
      userBalance.setTotalSpent(userBalance.getTotalSpent().add(amount));
      userBalanceRepository.save(userBalance);

      log.info("Deducted {} from user {}. New balance: {}", amount, userId, newBalance);

      // Publish balance update event for realtime notification
      publishBalanceUpdateEvent(userId, newBalance);

    } catch (PaymentException e) {
      throw e;
    } catch (Exception e) {
      log.error("Error deducting balance for user: {}", userId, e);
      throw new PaymentException("Failed to deduct balance", e);
    }
  }

  /**
   * Get balance directly from user_balances table for consistency and reporting This ensures data
   * consistency and better performance for reporting
   */
  @Transactional(readOnly = true)
  public BigDecimal getBalance(String userId) {
    try {
      // Get balance directly from user_balances table
      UserBalance userBalance = userBalanceRepository.findByUserId(userId)
          .orElseGet(() -> UserBalance.builder().userId(userId).balance(BigDecimal.ZERO)
              .totalDeposited(BigDecimal.ZERO).totalSpent(BigDecimal.ZERO).build());

      BigDecimal balance = userBalance.getBalance();

      log.debug("Retrieved balance from user_balances for user {}: balance={}", userId, balance);

      return balance;
    } catch (Exception e) {
      log.error("Error getting balance for user: {}", userId, e);
      throw new PaymentException("Failed to get balance", e);
    }
  }

  /**
   * Get detailed balance information directly from user_balances table
   */
  @Transactional(readOnly = true)
  public BalanceInfo getBalanceInfo(String userId) {
    try {
      UserBalance userBalance = userBalanceRepository.findByUserId(userId)
          .orElseGet(() -> UserBalance.builder().userId(userId).balance(BigDecimal.ZERO)
              .totalDeposited(BigDecimal.ZERO).totalSpent(BigDecimal.ZERO).build());

      return BalanceInfo.builder().userId(userId).balance(userBalance.getBalance())
          .totalDeposited(userBalance.getTotalDeposited()).totalSpent(userBalance.getTotalSpent())
          .build();
    } catch (Exception e) {
      log.error("Error getting balance info for user: {}", userId, e);
      throw new PaymentException("Failed to get balance info", e);
    }
  }

  /**
   * Synchronize user_balances with payment orders to ensure data consistency This method
   * recalculates balance from orders and updates user_balances table
   */
  @Transactional
  public void synchronizeBalanceFromOrders(String userId) {
    try {
      log.info("Starting balance synchronization for user: {}", userId);

      // Calculate total deposited from orders
      BigDecimal totalDeposited = calculateTotalDeposited(userId);

      // Get total spent from current user_balances
      BigDecimal totalSpent = getUserTotalSpent(userId);

      // Calculate new balance
      BigDecimal newBalance = totalDeposited.subtract(totalSpent);

      // Get or create user balance record
      UserBalance userBalance = userBalanceRepository.findByUserId(userId)
          .orElseGet(() -> UserBalance.builder().userId(userId).balance(BigDecimal.ZERO)
              .totalDeposited(BigDecimal.ZERO).totalSpent(BigDecimal.ZERO).build());

      // Update with calculated values
      userBalance.setBalance(newBalance);
      userBalance.setTotalDeposited(totalDeposited);
      userBalance.setTotalSpent(totalSpent);

      userBalanceRepository.save(userBalance);

      log.info("Balance synchronized for user {}: balance={}, totalDeposited={}, totalSpent={}",
          userId, newBalance, totalDeposited, totalSpent);

      // Publish balance update event for realtime notification
      publishBalanceUpdateEvent(userId, newBalance);

    } catch (Exception e) {
      log.error("Error synchronizing balance for user: {}", userId, e);
      throw new PaymentException("Failed to synchronize balance", e);
    }
  }

  /**
   * Calculate total deposited from completed and mismatch payment orders Both COMPLETED and
   * MISMATCH orders contribute to user balance
   */
  private BigDecimal calculateTotalDeposited(String userId) {
    try {
      // Get completed orders
      BigDecimal completedAmount =
          paymentOrderRepository.findByUserIdAndStatus(userId, PaymentStatus.COMPLETED).stream()
              .map(order -> order.getActualAmount() != null ? order.getActualAmount()
                  : order.getAmount())
              .reduce(BigDecimal.ZERO, BigDecimal::add);

      // Get mismatch orders (also contribute to balance)
      BigDecimal mismatchAmount =
          paymentOrderRepository.findByUserIdAndStatus(userId, PaymentStatus.MISMATCH).stream()
              .map(order -> order.getActualAmount() != null ? order.getActualAmount()
                  : order.getAmount())
              .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalDeposited = completedAmount.add(mismatchAmount);

      log.debug("Calculated total deposited for user {}: completed={}, mismatch={}, total={}",
          userId, completedAmount, mismatchAmount, totalDeposited);

      return totalDeposited;
    } catch (Exception e) {
      log.error("Error calculating total deposited for user: {}", userId, e);
      return BigDecimal.ZERO;
    }
  }

  /**
   * Get total spent from user_balances table
   */
  private BigDecimal getUserTotalSpent(String userId) {
    try {
      UserBalance userBalance = userBalanceRepository.findByUserId(userId)
          .orElseGet(() -> UserBalance.builder().userId(userId).balance(BigDecimal.ZERO)
              .totalDeposited(BigDecimal.ZERO).totalSpent(BigDecimal.ZERO).build());

      return userBalance.getTotalSpent();
    } catch (Exception e) {
      log.error("Error getting total spent for user: {}", userId, e);
      return BigDecimal.ZERO;
    }
  }

  /**
   * Publish balance update event for realtime notification This method publishes an event that will
   * be handled by the TransactionalEventListener
   */
  private void publishBalanceUpdateEvent(String userId, BigDecimal newBalance) {
    try {
      PaymentRealtimeService.BalanceUpdateEvent event =
          new PaymentRealtimeService.BalanceUpdateEvent(userId, newBalance);
      eventPublisher.publishEvent(event);
      log.debug("Published balance update event for user: {} with balance: {}", userId, newBalance);
    } catch (Exception e) {
      log.error("Error publishing balance update event for user: {} with balance: {}", userId,
          newBalance, e);
      // Don't throw exception to avoid affecting balance operations
    }
  }
}
