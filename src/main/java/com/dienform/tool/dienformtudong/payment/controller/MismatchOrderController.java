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
import com.dienform.tool.dienformtudong.payment.service.MismatchOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/payments/mismatch")
@RequiredArgsConstructor
@Slf4j
public class MismatchOrderController {

  private final MismatchOrderService mismatchOrderService;
  private final com.dienform.tool.dienformtudong.payment.repository.PaymentOrderRepository paymentOrderRepository;
  private final CurrentUserUtil currentUserUtil;

  /**
   * Get all mismatch orders for current user
   */
  @GetMapping
  public ResponseEntity<ResponseModel<List<MismatchOrderService.MismatchDetails>>> getMismatchOrders() {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();

      List<PaymentOrder> mismatchOrders =
          paymentOrderRepository.findByUserIdAndStatus(userId, PaymentStatus.MISMATCH);

      List<MismatchOrderService.MismatchDetails> mismatchDetails = mismatchOrders.stream()
          .map(mismatchOrderService::getMismatchDetails).collect(Collectors.toList());

      return ResponseEntity.ok(ResponseModel.success(mismatchDetails, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting mismatch orders", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel
          .error("Failed to get mismatch orders: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Get mismatch details for specific order
   */
  @GetMapping("/{orderId}")
  public ResponseEntity<ResponseModel<MismatchOrderService.MismatchDetails>> getMismatchOrderDetails(
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

      if (order.getStatus() != PaymentStatus.MISMATCH) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ResponseModel.error("Order is not a mismatch order", HttpStatus.BAD_REQUEST));
      }

      MismatchOrderService.MismatchDetails details = mismatchOrderService.getMismatchDetails(order);
      return ResponseEntity.ok(ResponseModel.success(details, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting mismatch order details for order: {}", orderId, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel.error(
          "Failed to get mismatch order details: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Get mismatch summary statistics for current user
   */
  @GetMapping("/summary")
  public ResponseEntity<ResponseModel<Map<String, Object>>> getMismatchSummary() {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();

      List<PaymentOrder> mismatchOrders =
          paymentOrderRepository.findByUserIdAndStatus(userId, PaymentStatus.MISMATCH);

      BigDecimal totalExpected = mismatchOrders.stream().map(PaymentOrder::getAmount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalActual = mismatchOrders.stream().map(
          order -> order.getActualAmount() != null ? order.getActualAmount() : order.getAmount())
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalDifference = totalActual.subtract(totalExpected);

      long overpaymentCount =
          mismatchOrders.stream().filter(order -> order.getActualAmount() != null
              && order.getActualAmount().compareTo(order.getAmount()) > 0).count();

      long underpaymentCount =
          mismatchOrders.stream().filter(order -> order.getActualAmount() != null
              && order.getActualAmount().compareTo(order.getAmount()) < 0).count();

      Map<String, Object> summary = Map.of("totalMismatchOrders", mismatchOrders.size(),
          "totalExpectedAmount", totalExpected, "totalActualAmount", totalActual, "totalDifference",
          totalDifference, "overpaymentCount", overpaymentCount, "underpaymentCount",
          underpaymentCount, "isNetOverpayment", totalDifference.compareTo(BigDecimal.ZERO) > 0);

      return ResponseEntity.ok(ResponseModel.success(summary, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting mismatch summary", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel
          .error("Failed to get mismatch summary: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }
}
