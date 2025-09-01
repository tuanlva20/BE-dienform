package com.dienform.common.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.dto.CreatePaymentOrderRequest;
import com.dienform.common.dto.FinancialReportResponse;
import com.dienform.common.dto.PaymentOrderSearchRequest;
import com.dienform.common.dto.ReportPaymentOrderResponse;
import com.dienform.common.model.ResponseModel;
import com.dienform.common.service.PaymentOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/payment-orders")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class PaymentOrderController {

  private final PaymentOrderService paymentOrderService;

  /**
   * Get financial report with total deposited, spent, and promotional amounts
   * 
   * @return Financial report data
   */
  @GetMapping("/financial-report")
  public ResponseEntity<ResponseModel<FinancialReportResponse>> getFinancialReport() {
    log.info("Received request for financial report");

    try {
      ResponseModel<FinancialReportResponse> response = paymentOrderService.getFinancialReport();
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("Error getting financial report: {}", e.getMessage(), e);
      return ResponseEntity.badRequest()
          .body(ResponseModel.error("Error retrieving financial report: " + e.getMessage(),
              org.springframework.http.HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Create new account promotion for a user (20,000 VND)
   * 
   * @param userId User ID to create promotion for
   * @return Success response
   */
  @PostMapping("/new-account-promotion/{userId}")
  public ResponseEntity<ResponseModel<String>> createNewAccountPromotion(
      @PathVariable UUID userId) {
    log.info("Creating new account promotion for user: {}", userId);

    try {
      paymentOrderService.createNewAccountPromotion(userId);
      return ResponseEntity.ok(ResponseModel.success("New account promotion created successfully",
          org.springframework.http.HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error creating new account promotion for user {}: {}", userId, e.getMessage(), e);
      return ResponseEntity.badRequest()
          .body(ResponseModel.error("Error creating new account promotion: " + e.getMessage(),
              org.springframework.http.HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Mark a promotional payment order as reported
   * 
   * @param paymentOrderId Payment order ID to mark as reported
   * @return Success response
   */
  @PostMapping("/mark-reported/{paymentOrderId}")
  public ResponseEntity<ResponseModel<String>> markPromotionAsReported(
      @PathVariable UUID paymentOrderId) {
    log.info("Marking promotion as reported: {}", paymentOrderId);

    try {
      paymentOrderService.markPromotionAsReported(paymentOrderId);
      return ResponseEntity.ok(ResponseModel.success("Promotion marked as reported successfully",
          org.springframework.http.HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error marking promotion as reported {}: {}", paymentOrderId, e.getMessage(), e);
      return ResponseEntity.badRequest()
          .body(ResponseModel.error("Error marking promotion as reported: " + e.getMessage(),
              org.springframework.http.HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Create a new payment order (deposit, withdrawal, or promotional)
   * 
   * @param request Payment order creation request
   * @return Success response
   */
  @PostMapping
  public ResponseEntity<ResponseModel<String>> createPaymentOrder(
      @Valid @RequestBody CreatePaymentOrderRequest request) {
    log.info("Creating payment order: {}", request);

    try {
      ResponseModel<String> response = paymentOrderService.createPaymentOrder(request);
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("Error creating payment order: {}", e.getMessage(), e);
      return ResponseEntity.badRequest()
          .body(ResponseModel.error("Error creating payment order: " + e.getMessage(),
              org.springframework.http.HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Get all payment orders with pagination and filtering
   * 
   * @param request Search and pagination parameters
   * @return Paginated list of payment orders
   */
  @PostMapping("/search")
  public ResponseEntity<ResponseModel<List<ReportPaymentOrderResponse>>> getPaymentOrders(
      @Valid @RequestBody PaymentOrderSearchRequest request) {
    log.info("Received payment order search request: {}", request);

    try {
      ResponseModel<List<ReportPaymentOrderResponse>> response =
          paymentOrderService.getPaymentOrders(request);
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("Error in getPaymentOrders: {}", e.getMessage(), e);
      return ResponseEntity.badRequest()
          .body(ResponseModel.error("Error retrieving payment orders: " + e.getMessage(),
              org.springframework.http.HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Get all payment orders with simple pagination (alternative endpoint)
   * 
   * @param page Page number (0-based)
   * @param size Page size
   * @param paymentType Filter by payment type
   * @param status Filter by status
   * @param userName Filter by user name
   * @param userEmail Filter by user email
   * @param isPromotional Filter by promotional flag
   * @param isReported Filter by reported flag
   * @return Paginated list of payment orders
   */
  @GetMapping
  public ResponseEntity<ResponseModel<List<ReportPaymentOrderResponse>>> getPaymentOrders(
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "10") Integer size,
      @RequestParam(required = false) String paymentType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String userName,
      @RequestParam(required = false) String userEmail,
      @RequestParam(required = false) Boolean isPromotional,
      @RequestParam(required = false) Boolean isReported) {

    log.info("Getting payment orders with page={}, size={}, paymentType={}, status={}", page, size,
        paymentType, status);

    try {
      PaymentOrderSearchRequest request =
          PaymentOrderSearchRequest.builder().page(page).size(size).sortBy("createdAt")
              .sortDirection("DESC").paymentType(paymentType).status(status).userName(userName)
              .userEmail(userEmail).isPromotional(isPromotional).isReported(isReported).build();

      ResponseModel<List<ReportPaymentOrderResponse>> response =
          paymentOrderService.getPaymentOrders(request);
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("Error in getPaymentOrders: {}", e.getMessage(), e);
      return ResponseEntity.badRequest()
          .body(ResponseModel.error("Error retrieving payment orders: " + e.getMessage(),
              org.springframework.http.HttpStatus.BAD_REQUEST));
    }
  }

  /**
   * Get payment orders for current user (replaces /api/v1/orders)
   * 
   * @param page Page number (0-based)
   * @param size Page size
   * @return Paginated list of payment orders for current user
   */
  @GetMapping("/orders")
  public ResponseEntity<ResponseModel<List<ReportPaymentOrderResponse>>> getUserOrders(
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "10") Integer size) {

    log.info("Getting payment orders for current user with page={}, size={}", page, size);

    try {
      // Create search request for current user only
      PaymentOrderSearchRequest request = PaymentOrderSearchRequest.builder().page(page).size(size)
          .sortBy("createdAt").sortDirection("DESC").build();

      ResponseModel<List<ReportPaymentOrderResponse>> response =
          paymentOrderService.getPaymentOrders(request);
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("Error getting user payment orders: {}", e.getMessage(), e);
      return ResponseEntity.badRequest()
          .body(ResponseModel.error("Error retrieving user payment orders: " + e.getMessage(),
              org.springframework.http.HttpStatus.BAD_REQUEST));
    }
  }
}
