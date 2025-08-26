package com.dienform.tool.dienformtudong.payment.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.payment.dto.request.SEPAYOrderRequest;
import com.dienform.tool.dienformtudong.payment.dto.request.SEPAYWebhookRequest;
import com.dienform.tool.dienformtudong.payment.dto.response.SEPAYOrderResponse;
import com.dienform.tool.dienformtudong.payment.dto.response.SEPAYPaymentStatus;
import com.dienform.tool.dienformtudong.payment.entity.PaymentOrder;
import com.dienform.tool.dienformtudong.payment.enums.PaymentStatus;
import com.dienform.tool.dienformtudong.payment.repository.PaymentOrderRepository;
import com.dienform.tool.dienformtudong.payment.service.PaymentRealtimeService;
import com.dienform.tool.dienformtudong.payment.service.SEPAYApiService;
import com.dienform.tool.dienformtudong.payment.service.SEPAYPaymentService;
import com.dienform.tool.dienformtudong.payment.service.UserBalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SEPAYPaymentServiceImpl implements SEPAYPaymentService {

  private final PaymentOrderRepository paymentOrderRepository;
  private final PaymentRealtimeService paymentRealtimeService;
  private final UserBalanceService userBalanceService;
  private final SEPAYApiService sepayApiService;

  @Value("${sepay.secret-key}")
  private String sepaySecretKey;

  @Value("${sepay.qr-base-url}")
  private String sepayQrBaseUrl;

  @Value("${sepay.api-url}")
  private String sepayApiUrl;

  @Override
  @Transactional
  public SEPAYOrderResponse createOrder(SEPAYOrderRequest request, String userId) {
    try {
      // Generate unique order ID
      String orderId = "TS" + System.currentTimeMillis() + RandomStringUtils.randomNumeric(4);

      // Create payment order
      PaymentOrder order = PaymentOrder.builder().userId(userId).amount(request.getAmount())
          .orderId(orderId).status(PaymentStatus.PENDING)
          .expiresAt(LocalDateTime.now().plusMinutes(15)).retryCount(0).build();

      // Generate QR code URL
      String qrCodeUrl = generateSEPAYQRUrl(orderId, request.getAmount());
      order.setQrCodeUrl(qrCodeUrl);

      paymentOrderRepository.save(order);

      log.info("Created SEPAY order: {} for user: {} with amount: {}", orderId, userId,
          request.getAmount());

      return SEPAYOrderResponse.builder().success(true).orderId(orderId).qrCodeUrl(qrCodeUrl)
          .amount(request.getAmount()).expiresAt(order.getExpiresAt())
          .message("Order created successfully").build();

    } catch (Exception e) {
      log.error("Error creating SEPAY order for user: {}", userId, e);
      return SEPAYOrderResponse.builder().success(false)
          .message("Failed to create order: " + e.getMessage()).build();
    }
  }

  @Override
  @Transactional
  public ResponseEntity<String> handleWebhook(SEPAYWebhookRequest webhook) {
    try {
      log.info("Received SEPAY webhook for order: {}", webhook.getOrderId());

      // Verify webhook signature
      if (!verifyWebhookSignature(webhook)) {
        log.warn("Invalid webhook signature for order: {}", webhook.getOrderId());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
      }

      // Find the order
      Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByOrderId(webhook.getOrderId());
      if (orderOpt.isEmpty()) {
        log.warn("Order not found: {}", webhook.getOrderId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Order not found");
      }

      PaymentOrder order = orderOpt.get();

      // Check if order is already processed
      if (order.getStatus() != PaymentStatus.PENDING) {
        log.info("Order {} already processed with status: {}", webhook.getOrderId(),
            order.getStatus());
        return ResponseEntity.ok("Order already processed");
      }

      // Process the payment
      if ("success".equalsIgnoreCase(webhook.getStatus())) {
        processSuccessfulPayment(order, webhook);
      } else {
        processFailedPayment(order, webhook);
      }

      return ResponseEntity.ok("Webhook processed successfully");

    } catch (Exception e) {
      log.error("Error processing SEPAY webhook for order: {}", webhook.getOrderId(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal server error");
    }
  }

  @Override
  public SEPAYPaymentStatus checkPaymentStatus(String orderId) {
    try {
      Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByOrderId(orderId);
      if (orderOpt.isEmpty()) {
        return SEPAYPaymentStatus.builder().success(false).message("Order not found").build();
      }

      PaymentOrder order = orderOpt.get();

      return SEPAYPaymentStatus.builder().orderId(orderId).status(order.getStatus())
          .amount(order.getAmount()).actualAmount(order.getActualAmount())
          .processedAt(order.getProcessedAt()).success(true)
          .message("Status retrieved successfully").build();

    } catch (Exception e) {
      log.error("Error checking payment status for order: {}", orderId, e);
      return SEPAYPaymentStatus.builder().success(false)
          .message("Error checking status: " + e.getMessage()).build();
    }
  }

  @Override
  @Transactional
  @Scheduled(fixedRate = 300000) // Run every 5 minutes
  public void processExpiredOrders() {
    try {
      List<PaymentOrder> expiredOrders =
          paymentOrderRepository.findExpiredOrders(PaymentStatus.PENDING, LocalDateTime.now());

      for (PaymentOrder order : expiredOrders) {
        order.setStatus(PaymentStatus.EXPIRED);
        order.setProcessedAt(LocalDateTime.now());
        paymentOrderRepository.save(order);

        log.info("Marked order {} as expired", order.getOrderId());
      }
    } catch (Exception e) {
      log.error("Error processing expired orders", e);
    }
  }

  @Override
  @Transactional
  @Scheduled(fixedRate = 300000) // Run every 5 minutes
  public void checkPendingOrdersForWebhook() {
    try {
      LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
      List<PaymentOrder> pendingOrders =
          paymentOrderRepository.findPendingOrdersForWebhookCheck(PaymentStatus.PENDING, threshold);

      for (PaymentOrder order : pendingOrders) {
        // Query SEPAY API to check transaction status
        checkOrderWithSEPAY(order);
      }
    } catch (Exception e) {
      log.error("Error checking pending orders for webhook", e);
    }
  }

  private String generateSEPAYQRUrl(String orderId, java.math.BigDecimal amount) {
    // Generate SEPAY QR URL based on their API format
    return String.format("%s?orderId=%s&amount=%s", sepayQrBaseUrl, orderId, amount);
  }

  private boolean verifyWebhookSignature(SEPAYWebhookRequest webhook) {
    try {
      String data = webhook.getOrderId() + webhook.getAmount() + webhook.getActualAmount()
          + webhook.getStatus() + webhook.getTimestamp();

      String expectedSignature = calculateHMAC(data, sepaySecretKey);
      return expectedSignature.equals(webhook.getSignature());
    } catch (Exception e) {
      log.error("Error verifying webhook signature", e);
      return false;
    }
  }

  private String calculateHMAC(String data, String key)
      throws NoSuchAlgorithmException, InvalidKeyException {
    Mac mac = Mac.getInstance("HmacSHA256");
    SecretKeySpec secretKeySpec =
        new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    mac.init(secretKeySpec);
    byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    return bytesToHex(hmacBytes);
  }

  private String bytesToHex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte b : bytes) {
      result.append(String.format("%02x", b));
    }
    return result.toString();
  }

  private void processSuccessfulPayment(PaymentOrder order, SEPAYWebhookRequest webhook) {
    order.setStatus(PaymentStatus.COMPLETED);
    order.setActualAmount(webhook.getActualAmount());
    order.setWebhookSignature(webhook.getSignature());
    order.setProcessedAt(LocalDateTime.now());

    // Check if amount matches
    if (order.getAmount().compareTo(webhook.getActualAmount()) != 0) {
      order.setStatus(PaymentStatus.MISMATCH);
      log.warn("Amount mismatch for order: {}. Expected: {}, Actual: {}", order.getOrderId(),
          order.getAmount(), webhook.getActualAmount());
    }

    paymentOrderRepository.save(order);

    // Add balance to user account
    if (order.getStatus() == PaymentStatus.COMPLETED) {
      userBalanceService.addBalance(order.getUserId(), webhook.getActualAmount());
    }

    // Emit realtime event
    paymentRealtimeService.emitPaymentSuccessEvent(order);

    log.info("Payment completed for order: {} with amount: {}", order.getOrderId(),
        webhook.getActualAmount());
  }

  private void processFailedPayment(PaymentOrder order, SEPAYWebhookRequest webhook) {
    order.setStatus(PaymentStatus.FAILED);
    order.setWebhookSignature(webhook.getSignature());
    order.setProcessedAt(LocalDateTime.now());
    paymentOrderRepository.save(order);

    log.info("Payment failed for order: {} with message: {}", order.getOrderId(),
        webhook.getMessage());
  }

  private void checkOrderWithSEPAY(PaymentOrder order) {
    try {
      // Call SEPAY API to check transaction status
      SEPAYPaymentStatus status = sepayApiService.checkTransactionStatus(order.getOrderId());

      if (status.isSuccess() && status.getStatus() != PaymentStatus.PENDING) {
        // Update order based on API response
        order.setStatus(status.getStatus());
        order.setActualAmount(status.getActualAmount());
        order.setProcessedAt(status.getProcessedAt());

        if (status.getStatus() == PaymentStatus.COMPLETED) {
          userBalanceService.addBalance(order.getUserId(), status.getActualAmount());
          paymentRealtimeService.emitPaymentSuccessEvent(order);
        }

        paymentOrderRepository.save(order);
        log.info("Updated order {} status to {} via SEPAY API", order.getOrderId(),
            status.getStatus());
      } else {
        // Update retry count
        order.setRetryCount(order.getRetryCount() + 1);
        order.setLastWebhookAttempt(LocalDateTime.now());
        paymentOrderRepository.save(order);

        log.info("Checked order {} with SEPAY API (attempt {})", order.getOrderId(),
            order.getRetryCount());
      }
    } catch (Exception e) {
      log.error("Error checking order {} with SEPAY API", order.getOrderId(), e);

      // Update retry count on error
      order.setRetryCount(order.getRetryCount() + 1);
      order.setLastWebhookAttempt(LocalDateTime.now());
      paymentOrderRepository.save(order);
    }
  }


}
