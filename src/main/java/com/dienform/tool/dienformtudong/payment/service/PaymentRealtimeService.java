package com.dienform.tool.dienformtudong.payment.service;

import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.payment.dto.PaymentSuccessEvent;
import com.dienform.tool.dienformtudong.payment.entity.PaymentOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentRealtimeService {

  public void emitPaymentSuccessEvent(PaymentOrder order) {
    try {
      PaymentSuccessEvent event = PaymentSuccessEvent.builder().orderId(order.getOrderId())
          .userId(order.getUserId()).amount(order.getAmount()).actualAmount(order.getActualAmount())
          .processedAt(order.getProcessedAt()).message("Thanh toán thành công").build();

      // TODO: Implement realtime emission
      // For now, just log the event
      log.info("Payment success event: {}", event);
    } catch (Exception e) {
      log.error("Error emitting payment success event for user: {}", order.getUserId(), e);
    }
  }
}
