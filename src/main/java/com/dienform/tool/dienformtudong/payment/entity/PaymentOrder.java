package com.dienform.tool.dienformtudong.payment.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.dienform.common.entity.AuditEntity;
import com.dienform.tool.dienformtudong.payment.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class PaymentOrder extends AuditEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "amount", nullable = false, precision = 15, scale = 2)
  private BigDecimal amount;

  @Column(name = "actual_amount", precision = 15, scale = 2)
  private BigDecimal actualAmount;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private PaymentStatus status;

  @Column(name = "order_id", unique = true, nullable = false)
  private String orderId;

  @Column(name = "qr_code_url", columnDefinition = "TEXT")
  private String qrCodeUrl;

  @Column(name = "webhook_signature", length = 500)
  private String webhookSignature;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "processed_at")
  private LocalDateTime processedAt;

  @Column(name = "retry_count")
  private Integer retryCount;

  @Column(name = "last_webhook_attempt")
  private LocalDateTime lastWebhookAttempt;
}
