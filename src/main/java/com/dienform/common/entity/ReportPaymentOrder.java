package com.dienform.common.entity;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "report_payment_orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportPaymentOrder extends AuditEntity {

  public enum PaymentType {
    DEPOSIT("Nạp tiền"), PROMOTIONAL("Khuyến mãi"), WITHDRAWAL("Rút tiền");

    private final String displayName;

    PaymentType(String displayName) {
      this.displayName = displayName;
    }

    public String getDisplayName() {
      return displayName;
    }
  }

  public enum PaymentStatus {
    COMPLETED("Hoàn thành"), PROCESSING("Đang xử lý"), CANCELLED("Hủy"), PENDING(
        "Chờ xử lý"), FAILED("Thất bại");

    private final String displayName;

    PaymentStatus(String displayName) {
      this.displayName = displayName;
    }

    public String getDisplayName() {
      return displayName;
    }
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "amount", nullable = false, precision = 15, scale = 2)
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_type", nullable = false)
  private PaymentType paymentType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private PaymentStatus status;

  @Column(name = "description", columnDefinition = "TEXT")
  private String description;

  @Column(name = "transaction_id")
  private String transactionId;

  @Column(name = "is_promotional", nullable = false)
  private Boolean isPromotional = false;

  @Column(name = "is_reported", nullable = false)
  private Boolean isReported = false;
}
