package com.dienform.tool.dienformtudong.payment.controller;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.model.ResponseModel;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.tool.dienformtudong.payment.entity.PaymentOrder;
import com.dienform.tool.dienformtudong.payment.enums.PaymentStatus;
import com.dienform.tool.dienformtudong.payment.service.PaymentRealtimeService;
import com.dienform.tool.dienformtudong.payment.service.UserBalanceService;
import com.dienform.tool.dienformtudong.payment.service.UserBalanceService.BalanceInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/payments/balance")
@RequiredArgsConstructor
@Slf4j
public class UserBalanceController {

  private final UserBalanceService userBalanceService;
  private final PaymentRealtimeService paymentRealtimeService;
  private final CurrentUserUtil currentUserUtil;
  private final com.dienform.tool.dienformtudong.payment.repository.PaymentOrderRepository paymentOrderRepository;

  @GetMapping
  public ResponseEntity<ResponseModel<BigDecimal>> getBalance() {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();

      // Calculate balance dynamically from payment orders and spending
      BigDecimal balance = userBalanceService.getBalance(userId);

      return ResponseEntity.ok(ResponseModel.success(balance, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting user balance", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
          ResponseModel.error("Failed to get balance: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  @GetMapping("/details")
  public ResponseEntity<ResponseModel<BalanceInfo>> getBalanceDetails() {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();

      // Get detailed balance information including breakdown
      BalanceInfo balanceInfo = userBalanceService.getBalanceInfo(userId);

      return ResponseEntity.ok(ResponseModel.success(balanceInfo, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting user balance details", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel
          .error("Failed to get balance details: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  @GetMapping("/verify")
  public ResponseEntity<ResponseModel<Map<String, Object>>> verifyBalanceCalculation() {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();

      // Get all completed payment orders for this user
      List<PaymentOrder> completedOrders =
          paymentOrderRepository.findByUserIdAndStatus(userId, PaymentStatus.COMPLETED);

      // Get all mismatch payment orders for this user (underpayment only)
      List<PaymentOrder> mismatchOrders =
          paymentOrderRepository.findByUserIdAndStatus(userId, PaymentStatus.MISMATCH);

      // Get all overpayment orders for this user (for reporting only, not added to balance)
      List<PaymentOrder> overpaymentOrders =
          paymentOrderRepository.findByUserIdAndStatus(userId, PaymentStatus.OVERPAYMENT);

      // Calculate total from completed orders
      BigDecimal totalFromCompletedOrders = completedOrders.stream().map(
          order -> order.getActualAmount() != null ? order.getActualAmount() : order.getAmount())
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      // Calculate total from mismatch orders (underpayment only - these are added to balance)
      BigDecimal totalFromMismatchOrders = mismatchOrders.stream().map(
          order -> order.getActualAmount() != null ? order.getActualAmount() : order.getAmount())
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      // Calculate total from overpayment orders (for reporting only - NOT added to balance)
      BigDecimal totalFromOverpaymentOrders = overpaymentOrders.stream().map(
          order -> order.getActualAmount() != null ? order.getActualAmount() : order.getAmount())
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      // Total from all orders that contribute to balance (completed + underpayment only)
      BigDecimal totalFromAllOrders = totalFromCompletedOrders.add(totalFromMismatchOrders);

      // Get current balance info from user_balances table
      BalanceInfo balanceInfo = userBalanceService.getBalanceInfo(userId);

      // Create verification report
      Map<String, Object> verification = new HashMap<>();
      verification.put("userId", userId);
      verification.put("completedOrdersCount", completedOrders.size());
      verification.put("mismatchOrdersCount", mismatchOrders.size());
      verification.put("overpaymentOrdersCount", overpaymentOrders.size());
      verification.put("totalFromCompletedOrders", totalFromCompletedOrders);
      verification.put("totalFromMismatchOrders", totalFromMismatchOrders);
      verification.put("totalFromOverpaymentOrders", totalFromOverpaymentOrders);
      verification.put("totalFromAllOrders", totalFromAllOrders);
      verification.put("storedBalance", balanceInfo.getBalance());
      verification.put("storedTotalDeposited", balanceInfo.getTotalDeposited());
      verification.put("storedTotalSpent", balanceInfo.getTotalSpent());
      verification.put("ordersMatch",
          totalFromAllOrders.compareTo(balanceInfo.getTotalDeposited()) == 0);
      verification.put("balanceSource", "user_balances_table");

      // Add completed orders
      verification.put("completedOrders",
          completedOrders.stream()
              .map(order -> Map.of("orderId", order.getOrderId(), "amount", order.getAmount(),
                  "actualAmount", order.getActualAmount(), "processedAt", order.getProcessedAt()))
              .toList());

      // Add mismatch orders
      verification.put("mismatchOrders",
          mismatchOrders.stream()
              .map(order -> Map.of("orderId", order.getOrderId(), "amount", order.getAmount(),
                  "actualAmount", order.getActualAmount(), "processedAt", order.getProcessedAt()))
              .toList());

      return ResponseEntity.ok(ResponseModel.success(verification, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error verifying balance calculation", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel
          .error("Failed to verify balance: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  @PostMapping("/refresh")
  public ResponseEntity<ResponseModel<String>> refreshBalance() {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();

      // Trigger balance update via socket
      paymentRealtimeService.emitBalanceUpdate(userId);

      return ResponseEntity.ok(ResponseModel.success("Balance refresh triggered", HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error refreshing user balance", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel
          .error("Failed to refresh balance: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  @PostMapping("/sync")
  public ResponseEntity<ResponseModel<Map<String, Object>>> synchronizeBalance() {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();

      // Synchronize balance from orders to user_balances table
      userBalanceService.synchronizeBalanceFromOrders(userId);

      // Get updated balance info
      BalanceInfo balanceInfo = userBalanceService.getBalanceInfo(userId);

      Map<String, Object> result = new HashMap<>();
      result.put("message", "Balance synchronized successfully");
      result.put("userId", userId);
      result.put("balance", balanceInfo.getBalance());
      result.put("totalDeposited", balanceInfo.getTotalDeposited());
      result.put("totalSpent", balanceInfo.getTotalSpent());

      return ResponseEntity.ok(ResponseModel.success(result, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error synchronizing user balance", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel
          .error("Failed to synchronize balance: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }
}
