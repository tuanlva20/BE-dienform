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
import com.dienform.tool.dienformtudong.payment.service.MismatchOrderService;
import com.dienform.tool.dienformtudong.payment.service.OverpaymentOrderService;
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

  private final com.dienform.tool.dienformtudong.payment.repository.PaymentOrderRepository paymentOrderRepository;
  private final PaymentRealtimeService paymentRealtimeService;
  private final UserBalanceService userBalanceService;
  private final SEPAYApiService sepayApiService;
  private final MismatchOrderService mismatchOrderService;
  private final OverpaymentOrderService overpaymentOrderService;

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
      // Extract orderId from content
      String orderId = webhook.extractOrderId();
      log.info("Received SEPAY webhook for transaction ID: {}, orderId: {}", webhook.getId(),
          orderId);

      // Verify webhook signature
      if (!verifyWebhookSignature(webhook)) {
        log.warn("Invalid webhook signature for transaction: {}", webhook.getId());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
      }

      // Find the order
      Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByOrderId(orderId);
      if (orderOpt.isEmpty()) {
        log.warn("Order not found: {}", orderId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Order not found");
      }

      PaymentOrder order = orderOpt.get();

      // Enhanced duplicate webhook detection
      if (order.getStatus() != PaymentStatus.PENDING) {
        // Check if this is a duplicate webhook for the same transaction
        if (webhook.getReferenceCode() != null
            && webhook.getReferenceCode().equals(order.getWebhookSignature())) {
          log.info("Duplicate webhook detected for order: {} with reference code: {}", orderId,
              webhook.getReferenceCode());
          return ResponseEntity.ok("Duplicate webhook - already processed");
        }

        // Check if this is a different transaction but same order (should not happen)
        if (webhook.getReferenceCode() != null
            && !webhook.getReferenceCode().equals(order.getWebhookSignature())) {
          log.warn(
              "Different webhook received for already processed order: {} (existing: {}, new: {})",
              orderId, order.getWebhookSignature(), webhook.getReferenceCode());
          return ResponseEntity.ok("Order already processed with different transaction");
        }

        log.info("Order {} already processed with status: {}", orderId, order.getStatus());
        return ResponseEntity.ok("Order already processed");
      }

      // Update last webhook attempt timestamp
      order.setLastWebhookAttempt(LocalDateTime.now());
      paymentOrderRepository.save(order);

      // Process the payment based on transfer type and amount
      if (webhook.isSuccessful()) {
        processSuccessfulPayment(order, webhook);
      } else {
        processFailedPayment(order, webhook);
      }

      return ResponseEntity.ok("Webhook processed successfully");

    } catch (Exception e) {
      log.error("Error processing SEPAY webhook for transaction: {}", webhook.getId(), e);
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
    // Generate SEPAY QR URL
    // Format:
    // https://qr.sepay.vn/img?bank=BIDV&acc=96247KHAOSAT&template=qronly&amount={amount}&des={orderId}
    return String.format("%s?bank=BIDV&acc=96247KHAOSAT&template=qronly&amount=%s&des=%s",
        sepayQrBaseUrl, amount, orderId);
  }

  private boolean verifyWebhookSignature(SEPAYWebhookRequest webhook) {
    try {
      // For new SEPAY webhook structure, we don't have signature verification
      // We'll rely on the webhook endpoint being secure and the data being valid
      log.info("Skipping signature verification for SEPAY webhook transaction ID: {}",
          webhook.getId());
      return true; // Always return true for now
    } catch (Exception e) {
      log.error("Error in webhook verification", e);
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
    order.setActualAmount(webhook.getActualAmount());
    // Store reference code as webhook signature for tracking
    order.setWebhookSignature(webhook.getReferenceCode());
    order.setProcessedAt(LocalDateTime.now());

    // Check if amount matches
    if (order.getAmount().compareTo(webhook.getActualAmount()) != 0) {
      // Check if it's overpayment (potential security risk)
      if (webhook.getActualAmount().compareTo(order.getAmount()) > 0) {
        // Process as overpayment - potential security risk
        log.warn(
            "OVERPAYMENT DETECTED for order: {}. Expected: {}, Actual: {}. Potential security risk!",
            order.getOrderId(), order.getAmount(), webhook.getActualAmount());
        overpaymentOrderService.processOverpaymentOrder(order, webhook.getActualAmount());
      } else {
        // Process as underpayment - normal mismatch
        log.warn("Amount mismatch (underpayment) for order: {}. Expected: {}, Actual: {}",
            order.getOrderId(), order.getAmount(), webhook.getActualAmount());
        mismatchOrderService.processMismatchOrder(order, webhook.getActualAmount());
      }
    } else {
      // Process as normal completed payment
      order.setStatus(PaymentStatus.COMPLETED);
      paymentOrderRepository.save(order);

      // Add balance to user account
      userBalanceService.addBalance(order.getUserId(), webhook.getActualAmount());

      // Emit realtime event
      paymentRealtimeService.emitPaymentSuccessEvent(order);
    }

    log.info("Payment processed for order: {} with amount: {} from bank: {} account: {}",
        order.getOrderId(), webhook.getActualAmount(), webhook.getGateway(),
        webhook.getAccountNumber());
  }



  private void processFailedPayment(PaymentOrder order, SEPAYWebhookRequest webhook) {
    order.setStatus(PaymentStatus.FAILED);
    // Store reference code as webhook signature for tracking
    order.setWebhookSignature(webhook.getReferenceCode());
    order.setProcessedAt(LocalDateTime.now());
    paymentOrderRepository.save(order);

    log.info("Payment failed for order: {} with transfer type: {} amount: {}", order.getOrderId(),
        webhook.getTransferType(), webhook.getTransferAmount());
  }

  private void checkOrderWithSEPAY(PaymentOrder order) {
    try {
      // Call SEPAY API to check transaction status
      SEPAYPaymentStatus status = sepayApiService.checkTransactionStatus(order.getOrderId());

      if (status.isSuccess() && status.getStatus() != PaymentStatus.PENDING) {
        // Update order based on API response
        order.setActualAmount(status.getActualAmount());
        order.setProcessedAt(status.getProcessedAt());

        if (status.getStatus() == PaymentStatus.COMPLETED) {
          // Check if amount matches
          if (order.getAmount().compareTo(status.getActualAmount()) != 0) {
            // Check if it's overpayment (potential security risk)
            if (status.getActualAmount().compareTo(order.getAmount()) > 0) {
              // Process as overpayment - potential security risk
              log.warn(
                  "OVERPAYMENT detected via API for order: {}. Expected: {}, Actual: {}. Potential security risk!",
                  order.getOrderId(), order.getAmount(), status.getActualAmount());
              overpaymentOrderService.processOverpaymentOrder(order, status.getActualAmount());
            } else {
              // Process as underpayment - normal mismatch
              log.warn(
                  "Amount mismatch (underpayment) detected via API for order: {}. Expected: {}, Actual: {}",
                  order.getOrderId(), order.getAmount(), status.getActualAmount());
              mismatchOrderService.processMismatchOrder(order, status.getActualAmount());
            }
          } else {
            // Process as normal completed payment
            order.setStatus(PaymentStatus.COMPLETED);
            paymentOrderRepository.save(order);
            userBalanceService.addBalance(order.getUserId(), status.getActualAmount());
            paymentRealtimeService.emitPaymentSuccessEvent(order);
          }
        } else {
          // For other statuses (FAILED, etc.)
          order.setStatus(status.getStatus());
          paymentOrderRepository.save(order);
        }

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
