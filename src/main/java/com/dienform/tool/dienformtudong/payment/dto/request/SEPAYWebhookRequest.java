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
      // Pattern to match TS number between dots and before .CT
      // Example: MBVCB.10782070993.137550.TS17568965085753886.CT
      // We want to extract: TS17568965085753886

      // Method 1: Using regex to find TS number between dots
      java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\.(TS\\d+)\\.");
      java.util.regex.Matcher matcher = pattern.matcher(content);

      if (matcher.find()) {
        String orderId = matcher.group(1);
        return orderId;
      }

      // Method 2: Fallback - split by dots and find TS number
      String[] parts = content.split("\\.");
      for (String part : parts) {
        if (part.startsWith("TS") && part.length() > 10) {
          return part;
        }
      }

      // Method 3: Fallback - find TS number anywhere in content
      java.util.regex.Pattern tsPattern = java.util.regex.Pattern.compile("TS\\d+");
      java.util.regex.Matcher tsMatcher = tsPattern.matcher(content);
      if (tsMatcher.find()) {
        String orderId = tsMatcher.group();
        return orderId;
      }
    }
    return null; // No order ID found
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

  // Helper method to get detailed extraction info for debugging
  public String getExtractionInfo() {
    if (content == null || content.isBlank()) {
      return "Content is null or empty";
    }

    StringBuilder info = new StringBuilder();
    info.append("Content: ").append(content).append("\n");

    // Check for TS pattern between dots
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\.(TS\\d+)\\.");
    java.util.regex.Matcher matcher = pattern.matcher(content);
    if (matcher.find()) {
      info.append("Found TS number between dots: ").append(matcher.group(1)).append("\n");
    }

    // Check for TS pattern anywhere
    java.util.regex.Pattern tsPattern = java.util.regex.Pattern.compile("TS\\d+");
    java.util.regex.Matcher tsMatcher = tsPattern.matcher(content);
    if (tsMatcher.find()) {
      info.append("Found TS number anywhere: ").append(tsMatcher.group()).append("\n");
    }

    return info.toString();
  }

  // Helper method to check if content contains order ID pattern
  public boolean hasOrderIdPattern() {
    return content != null && content.matches(".*TS\\d+.*");
  }
}
