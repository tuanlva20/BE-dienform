package com.dienform.tool.dienformtudong.payment.enums;

public enum PaymentStatus {
  PENDING, // Chờ thanh toán
  COMPLETED, // Thanh toán thành công
  FAILED, // Thanh toán thất bại
  EXPIRED, // Hết hạn
  MISMATCH // Số tiền không khớp
}
