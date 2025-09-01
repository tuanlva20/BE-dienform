package com.dienform.common.service;

import java.util.List;
import java.util.UUID;
import com.dienform.common.dto.CreatePaymentOrderRequest;
import com.dienform.common.dto.FinancialReportResponse;
import com.dienform.common.dto.PaymentOrderSearchRequest;
import com.dienform.common.dto.ReportPaymentOrderResponse;
import com.dienform.common.model.ResponseModel;

public interface PaymentOrderService {

  ResponseModel<FinancialReportResponse> getFinancialReport();

  void createNewAccountPromotion(UUID userId);

  void markPromotionAsReported(UUID paymentOrderId);

  ResponseModel<String> createPaymentOrder(CreatePaymentOrderRequest request);

  ResponseModel<List<ReportPaymentOrderResponse>> getPaymentOrders(
      PaymentOrderSearchRequest request);
}
