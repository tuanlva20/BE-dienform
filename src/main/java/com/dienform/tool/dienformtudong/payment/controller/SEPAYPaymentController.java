package com.dienform.tool.dienformtudong.payment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.model.ResponseModel;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.tool.dienformtudong.payment.dto.request.SEPAYOrderRequest;
import com.dienform.tool.dienformtudong.payment.dto.request.SEPAYWebhookRequest;
import com.dienform.tool.dienformtudong.payment.dto.response.SEPAYOrderResponse;
import com.dienform.tool.dienformtudong.payment.dto.response.SEPAYPaymentStatus;
import com.dienform.tool.dienformtudong.payment.service.SEPAYPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/payments/sepay")
@RequiredArgsConstructor
@Slf4j
public class SEPAYPaymentController {

  private final SEPAYPaymentService sepayPaymentService;
  private final CurrentUserUtil currentUserUtil;

  @PostMapping("/create-order")
  public ResponseEntity<ResponseModel<SEPAYOrderResponse>> createOrder(
      @Valid @RequestBody SEPAYOrderRequest request) {

    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();
      SEPAYOrderResponse response = sepayPaymentService.createOrder(request, userId);

      if (response.isSuccess()) {
        return ResponseEntity.ok(ResponseModel.success(response, HttpStatus.OK));
      } else {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ResponseModel.error(response.getMessage(), HttpStatus.BAD_REQUEST));
      }
    } catch (Exception e) {
      log.error("Error creating SEPAY order", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
          ResponseModel.error("Failed to create order: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  @PostMapping("/webhook")
  public ResponseEntity<String> handleWebhook(@RequestBody SEPAYWebhookRequest webhook) {
    try {
      return sepayPaymentService.handleWebhook(webhook);
    } catch (Exception e) {
      log.error("Error handling SEPAY webhook", e);
      return ResponseEntity.internalServerError().body("Internal server error");
    }
  }

  @GetMapping("/{orderId}/status")
  public ResponseEntity<ResponseModel<SEPAYPaymentStatus>> checkPaymentStatus(
      @PathVariable String orderId) {
    try {
      SEPAYPaymentStatus status = sepayPaymentService.checkPaymentStatus(orderId);

      if (status.isSuccess()) {
        return ResponseEntity.ok(ResponseModel.success(status, HttpStatus.OK));
      } else {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ResponseModel.error(status.getMessage(), HttpStatus.BAD_REQUEST));
      }
    } catch (Exception e) {
      log.error("Error checking payment status for order: {}", orderId, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel
          .error("Failed to check payment status: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  @GetMapping("/user/orders")
  public ResponseEntity<ResponseModel<Object>> getUserOrders() {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();
      // TODO: Implement get user orders
      return ResponseEntity
          .ok(ResponseModel.success("User orders retrieved successfully", HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting user orders", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseModel
          .error("Failed to get user orders: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }
}
