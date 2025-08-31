package com.dienform.tool.dienformtudong.payment.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.dienform.tool.dienformtudong.payment.dto.response.SEPAYPaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SEPAYApiService {

  private final RestTemplate restTemplate;

  @Value("${sepay.api-url}")
  private String sepayApiUrl;

  @Value("${sepay.secret-key}")
  private String sepaySecretKey;

  public SEPAYPaymentStatus checkTransactionStatus(String orderId) {
    try {
      String url = sepayApiUrl + "/transaction/status/" + orderId;

      HttpHeaders headers = new HttpHeaders();
      headers.set("Authorization", "Apikey " + sepaySecretKey);
      headers.set("Content-Type", "application/json");

      HttpEntity<String> entity = new HttpEntity<>(headers);

      ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

      if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
        Map<String, Object> data = response.getBody();

        return SEPAYPaymentStatus.builder().orderId(orderId)
            .status(parseStatus((String) data.get("status")))
            .amount(new BigDecimal(data.get("amount").toString()))
            .actualAmount(new BigDecimal(data.get("actualAmount").toString()))
            .processedAt(LocalDateTime.now()).success(true)
            .message("Transaction status retrieved successfully").build();
      }

      return SEPAYPaymentStatus.builder().orderId(orderId).success(false)
          .message("Failed to retrieve transaction status").build();

    } catch (Exception e) {
      log.error("Error checking SEPAY transaction status for order: {}", orderId, e);
      return SEPAYPaymentStatus.builder().orderId(orderId).success(false)
          .message("Error checking transaction status: " + e.getMessage()).build();
    }
  }

  private com.dienform.tool.dienformtudong.payment.enums.PaymentStatus parseStatus(String status) {
    if ("success".equalsIgnoreCase(status)) {
      return com.dienform.tool.dienformtudong.payment.enums.PaymentStatus.COMPLETED;
    } else if ("failed".equalsIgnoreCase(status)) {
      return com.dienform.tool.dienformtudong.payment.enums.PaymentStatus.FAILED;
    } else {
      return com.dienform.tool.dienformtudong.payment.enums.PaymentStatus.PENDING;
    }
  }
}
