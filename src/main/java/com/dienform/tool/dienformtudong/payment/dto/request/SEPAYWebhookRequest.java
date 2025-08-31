package com.dienform.tool.dienformtudong.payment.dto.request;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SEPAYWebhookRequest {

  private Long id; // ID giao dịch trên SePay
  private String gateway; // Brand name của ngân hàng
  private String transactionDate; // Thời gian xảy ra giao dịch phía ngân hàng
  private String accountNumber; // Số tài khoản ngân hàng
  private String content; // Nội dung chuyển khoản (orderId)
  private String transferType; // Loại giao dịch. in là tiền vào, out là tiền ra
  private BigDecimal transferAmount; // Số tiền giao dịch
  private String referenceCode; // Mã tham chiếu của tin nhắn sms

  // Helper methods to extract orderId from content
  public String extractOrderId() {
    if (content != null && !content.isBlank()) {
      // Try to extract orderId from content (assuming format like "TS1234567890")
      String[] parts = content.split("\\s+");
      for (String part : parts) {
        if (part.startsWith("TS") && part.length() > 10) {
          return part;
        }
      }
    }
    return null; // No fallback needed since we only use content
  }

  // Helper method to determine if payment is successful
  public boolean isSuccessful() {
    return "in".equalsIgnoreCase(transferType) && transferAmount != null
        && transferAmount.compareTo(BigDecimal.ZERO) > 0;
  }

  // Helper method to get actual amount
  public BigDecimal getActualAmount() {
    return transferAmount;
  }

  // Helper method to get transaction timestamp
  public String getTransactionTimestamp() {
    return transactionDate;
  }
}
