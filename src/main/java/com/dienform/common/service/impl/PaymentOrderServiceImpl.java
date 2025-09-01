package com.dienform.common.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.dto.CreatePaymentOrderRequest;
import com.dienform.common.dto.FinancialReportResponse;
import com.dienform.common.dto.PaymentOrderSearchRequest;
import com.dienform.common.dto.ReportPaymentOrderResponse;
import com.dienform.common.entity.ReportPaymentOrder;
import com.dienform.common.entity.User;
import com.dienform.common.model.ResponseModel;
import com.dienform.common.repository.ReportPaymentOrderRepository;
import com.dienform.common.repository.UserRepository;
import com.dienform.common.service.PaymentOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentOrderServiceImpl implements PaymentOrderService {

  private static final BigDecimal NEW_ACCOUNT_PROMOTION_AMOUNT = new BigDecimal("20000");
  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final ReportPaymentOrderRepository paymentOrderRepository;
  private final UserRepository userRepository;

  @Override
  public ResponseModel<FinancialReportResponse> getFinancialReport() {
    log.info("Generating financial report");

    try {
      // Calculate total deposited from DEPOSIT payment orders
      BigDecimal totalDeposited = paymentOrderRepository.getTotalDeposited();
      BigDecimal totalSpent = paymentOrderRepository.getTotalSpent();
      BigDecimal totalPromotional = paymentOrderRepository.getTotalPromotional();

      FinancialReportResponse report =
          FinancialReportResponse.builder().totalDeposited(totalDeposited).totalSpent(totalSpent)
              .totalPromotional(totalPromotional).build();

      log.info("Financial report generated - Deposited: {}, Spent: {}, Promotional: {}",
          totalDeposited, totalSpent, totalPromotional);

      return ResponseModel.success(report, HttpStatus.OK);

    } catch (Exception e) {
      log.error("Error generating financial report: {}", e.getMessage(), e);
      return ResponseModel.error("Error generating financial report: " + e.getMessage(),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  @Override
  @Transactional
  public void createNewAccountPromotion(UUID userId) {
    log.info("Creating new account promotion for user: {}", userId);

    try {
      // Check if user exists
      User user = userRepository.findById(userId)
          .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

      // Check if user has already received new account promotion
      if (paymentOrderRepository.hasUserReceivedNewAccountPromotion(userId)) {
        log.info("User {} has already received new account promotion", userId);
        return;
      }

      // Create promotional payment order
      ReportPaymentOrder promotionalOrder =
          ReportPaymentOrder.builder().user(user).amount(NEW_ACCOUNT_PROMOTION_AMOUNT)
              .paymentType(ReportPaymentOrder.PaymentType.PROMOTIONAL)
              .status(ReportPaymentOrder.PaymentStatus.COMPLETED)
              .description("Khuyến mãi tài khoản mới - 20.000 VND")
              .transactionId("PROMO_" + System.currentTimeMillis()).isPromotional(true)
              .isReported(false).build();

      paymentOrderRepository.save(promotionalOrder);

      log.info("Created new account promotion for user {}: {} VND", userId,
          NEW_ACCOUNT_PROMOTION_AMOUNT);

    } catch (Exception e) {
      log.error("Error creating new account promotion for user {}: {}", userId, e.getMessage(), e);
      throw new RuntimeException("Failed to create new account promotion: " + e.getMessage());
    }
  }

  @Override
  @Transactional
  public void markPromotionAsReported(UUID paymentOrderId) {
    log.info("Marking promotion as reported: {}", paymentOrderId);

    try {
      ReportPaymentOrder paymentOrder = paymentOrderRepository.findById(paymentOrderId)
          .orElseThrow(() -> new RuntimeException("Payment order not found: " + paymentOrderId));

      if (!paymentOrder.getIsPromotional()) {
        throw new RuntimeException("Payment order is not promotional: " + paymentOrderId);
      }

      paymentOrder.setIsReported(true);
      paymentOrderRepository.save(paymentOrder);

      log.info("Marked promotion as reported: {}", paymentOrderId);

    } catch (Exception e) {
      log.error("Error marking promotion as reported {}: {}", paymentOrderId, e.getMessage(), e);
      throw new RuntimeException("Failed to mark promotion as reported: " + e.getMessage());
    }
  }

  @Override
  @Transactional
  public ResponseModel<String> createPaymentOrder(CreatePaymentOrderRequest request) {
    log.info("Creating payment order for user: {}, amount: {}, type: {}", request.getUserId(),
        request.getAmount(), request.getPaymentType());

    try {
      // Check if user exists
      User user = userRepository.findById(request.getUserId()).orElseThrow(
          () -> new RuntimeException("User not found with id: " + request.getUserId()));

      // Parse payment type
      ReportPaymentOrder.PaymentType paymentType;
      try {
        paymentType =
            ReportPaymentOrder.PaymentType.valueOf(request.getPaymentType().toUpperCase());
      } catch (IllegalArgumentException e) {
        return ResponseModel.error("Invalid payment type: " + request.getPaymentType(),
            HttpStatus.BAD_REQUEST);
      }

      // Generate transactionId if not provided
      String transactionId = request.getTransactionId();
      if (transactionId == null || transactionId.trim().isEmpty()) {
        if (paymentType == ReportPaymentOrder.PaymentType.DEPOSIT) {
          // For deposit payments, create a user-friendly orderId
          transactionId = "DF" + System.currentTimeMillis();
        } else {
          // For other payment types, use timestamp
          transactionId = paymentType.name() + "_" + System.currentTimeMillis();
        }
      }

      // Create payment order
      ReportPaymentOrder paymentOrder =
          ReportPaymentOrder.builder().user(user).amount(request.getAmount())
              .paymentType(paymentType).status(ReportPaymentOrder.PaymentStatus.COMPLETED)
              .description(request.getDescription()).transactionId(transactionId)
              .isPromotional(ReportPaymentOrder.PaymentType.PROMOTIONAL.equals(paymentType))
              .isReported(false).build();

      paymentOrderRepository.save(paymentOrder);

      log.info("Created payment order: {} for user: {}", paymentOrder.getId(), user.getId());

      return ResponseModel.success("Payment order created successfully", HttpStatus.OK);

    } catch (Exception e) {
      log.error("Error creating payment order: {}", e.getMessage(), e);
      return ResponseModel.error("Error creating payment order: " + e.getMessage(),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  @Override
  public ResponseModel<List<ReportPaymentOrderResponse>> getPaymentOrders(
      PaymentOrderSearchRequest request) {
    log.info("Getting payment orders with filters: {}", request);

    try {
      // Create pageable with sorting
      String sortBy = request.getSortBy();
      if (sortBy == null || sortBy.trim().isEmpty()) {
        sortBy = "createdAt"; // Default sort field
      }
      Sort sort = Sort.by("DESC".equalsIgnoreCase(request.getSortDirection()) ? Sort.Direction.DESC
          : Sort.Direction.ASC, sortBy);
      Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), sort);

      // Parse date filters
      LocalDateTime fromDate = null;
      LocalDateTime toDate = null;

      if (request.getFromDate() != null && !request.getFromDate().trim().isEmpty()) {
        fromDate = LocalDateTime.parse(request.getFromDate(), DATE_FORMATTER);
      }

      if (request.getToDate() != null && !request.getToDate().trim().isEmpty()) {
        toDate = LocalDateTime.parse(request.getToDate(), DATE_FORMATTER);
      }

      // Parse payment type enum
      ReportPaymentOrder.PaymentType paymentType = null;
      if (request.getPaymentType() != null && !request.getPaymentType().trim().isEmpty()) {
        try {
          paymentType =
              ReportPaymentOrder.PaymentType.valueOf(request.getPaymentType().toUpperCase());
        } catch (IllegalArgumentException e) {
          log.warn("Invalid payment type provided: {}", request.getPaymentType());
        }
      }

      // Parse status enum
      ReportPaymentOrder.PaymentStatus status = null;
      if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
        try {
          status = ReportPaymentOrder.PaymentStatus.valueOf(request.getStatus().toUpperCase());
        } catch (IllegalArgumentException e) {
          log.warn("Invalid status provided: {}", request.getStatus());
        }
      }

      // Execute query
      Page<ReportPaymentOrder> paymentOrderPage =
          paymentOrderRepository.findPaymentOrdersWithFilters(paymentType, status,
              request.getUserName(), request.getUserEmail(), request.getIsPromotional(),
              request.getIsReported(), fromDate, toDate, pageable);

      // Convert to response DTOs
      List<ReportPaymentOrderResponse> paymentOrderResponses = paymentOrderPage.getContent()
          .stream().map(ReportPaymentOrderResponse::fromEntity).collect(Collectors.toList());

      log.info("Found {} payment orders out of {} total", paymentOrderResponses.size(),
          paymentOrderPage.getTotalElements());

      return ResponseModel.success(paymentOrderResponses, paymentOrderPage.getTotalPages(),
          request.getPage(), request.getSize(), paymentOrderPage.getTotalElements(), HttpStatus.OK);

    } catch (Exception e) {
      log.error("Error getting payment orders: {}", e.getMessage(), e);
      return ResponseModel.error("Error retrieving payment orders: " + e.getMessage(),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }
}
