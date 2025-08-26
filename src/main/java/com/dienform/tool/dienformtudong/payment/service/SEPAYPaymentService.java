package com.dienform.tool.dienformtudong.payment.service;

import org.springframework.http.ResponseEntity;
import com.dienform.tool.dienformtudong.payment.dto.request.SEPAYOrderRequest;
import com.dienform.tool.dienformtudong.payment.dto.request.SEPAYWebhookRequest;
import com.dienform.tool.dienformtudong.payment.dto.response.SEPAYOrderResponse;
import com.dienform.tool.dienformtudong.payment.dto.response.SEPAYPaymentStatus;

public interface SEPAYPaymentService {

  SEPAYOrderResponse createOrder(SEPAYOrderRequest request, String userId);

  ResponseEntity<String> handleWebhook(SEPAYWebhookRequest webhook);

  SEPAYPaymentStatus checkPaymentStatus(String orderId);

  void processExpiredOrders();

  void checkPendingOrdersForWebhook();
}
