package com.dienform.tool.dienformtudong.payment.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.model.ResponseModel;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.tool.dienformtudong.payment.entity.PaymentOrder;
import com.dienform.tool.dienformtudong.payment.enums.PaymentStatus;
import com.dienform.tool.dienformtudong.payment.repository.PaymentOrderRepository;
import com.dienform.tool.dienformtudong.payment.service.OverpaymentOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/payments/overpayment")
@RequiredArgsConstructor
@Slf4j
public class OverpaymentOrderController {

  private final OverpaymentOrderService overpaymentOrderService;
  private final PaymentOrderRepository paymentOrderRepository;
  private final CurrentUserUtil currentUserUtil;

  /**
   * Get all overpayment orders for current user
   */
  @GetMapping
  public ResponseEntity<ResponseModel<List<OverpaymentOrderService.OverpaymentDetails>>> getOverpaymentOrders() {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();

      List<PaymentOrder> overpaymentOrders =
          paymentOrderRepository.findByUserIdAndStatus(userId, PaymentStatus.OVERPAYMENT);

      List<OverpaymentOrderService.OverpaymentDetails> overpaymentDetails = overpaymentOrders.stream()
          .map(overpaymentOrderService::getOverpaymentDetails).collect(Collectors.toList());

      return ResponseEntity.ok(ResponseModel.success(overpaymentDetails, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting overpayment orders", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel
          .error("Failed to get overpayment orders: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Get overpayment details for specific order
   */
  @GetMapping("/{orderId}")
  public ResponseEntity<ResponseModel<OverpaymentOrderService.OverpaymentDetails>> getOverpaymentOrderDetails(
      @PathVariable String orderId) {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();

      PaymentOrder order = paymentOrderRepository.findByOrderId(orderId)
          .orElseThrow(() -> new RuntimeException("Order not found"));

      // Verify the order belongs to the current user
      if (!order.getUserId().equals(userId)) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ResponseModel
            .error("Access denied - order does not belong to current user", HttpStatus.FORBIDDEN));
      }

      if (order.getStatus() != PaymentStatus.OVERPAYMENT) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ResponseModel.error("Order is not an overpayment order", HttpStatus.BAD_REQUEST));
      }

      OverpaymentOrderService.OverpaymentDetails details = overpaymentOrderService.getOverpaymentDetails(order);
      return ResponseEntity.ok(ResponseModel.success(details, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting overpayment order details for order: {}", orderId, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel.error(
          "Failed to get overpayment order details: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Get overpayment summary statistics for current user
   */
  @GetMapping("/summary")
  public ResponseEntity<ResponseModel<Map<String, Object>>> getOverpaymentSummary() {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();

      List<PaymentOrder> overpaymentOrders =
          paymentOrderRepository.findByUserIdAndStatus(userId, PaymentStatus.OVERPAYMENT);

      BigDecimal totalExpected = overpaymentOrders.stream().map(PaymentOrder::getAmount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalActual = overpaymentOrders.stream().map(
          order -> order.getActualAmount() != null ? order.getActualAmount() : order.getAmount())
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalExcess = totalActual.subtract(totalExpected);

      long highRiskCount = overpaymentOrders.stream()
          .filter(order -> overpaymentOrderService.getOverpaymentDetails(order).getSecurityRiskLevel().equals("HIGH"))
          .count();

      long mediumRiskCount = overpaymentOrders.stream()
          .filter(order -> overpaymentOrderService.getOverpaymentDetails(order).getSecurityRiskLevel().equals("MEDIUM"))
          .count();

      long lowRiskCount = overpaymentOrders.stream()
          .filter(order -> overpaymentOrderService.getOverpaymentDetails(order).getSecurityRiskLevel().equals("LOW"))
          .count();

      Map<String, Object> summary = Map.of(
          "totalOverpaymentOrders", overpaymentOrders.size(),
          "totalExpectedAmount", totalExpected,
          "totalActualAmount", totalActual,
          "totalExcessAmount", totalExcess,
          "highRiskCount", highRiskCount,
          "mediumRiskCount", mediumRiskCount,
          "lowRiskCount", lowRiskCount,
          "securityRiskLevel", determineOverallRiskLevel(highRiskCount, mediumRiskCount, lowRiskCount)
      );

      return ResponseEntity.ok(ResponseModel.success(summary, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting overpayment summary", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel
          .error("Failed to get overpayment summary: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Determine overall security risk level
   */
  private String determineOverallRiskLevel(long highRiskCount, long mediumRiskCount, long lowRiskCount) {
    if (highRiskCount > 0) {
      return "HIGH";
    } else if (mediumRiskCount > 0) {
      return "MEDIUM";
    } else if (lowRiskCount > 0) {
      return "LOW";
    } else {
      return "NONE";
    }
  }
}
