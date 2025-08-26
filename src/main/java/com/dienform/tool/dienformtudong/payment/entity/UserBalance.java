package com.dienform.tool.dienformtudong.payment.entity;

import java.math.BigDecimal;
import java.util.UUID;
import com.dienform.common.entity.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_balances")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserBalance extends AuditEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "user_id", nullable = false, unique = true)
  private String userId;

  @Column(name = "balance", nullable = false, precision = 15, scale = 2)
  private BigDecimal balance;

  @Column(name = "total_deposited", precision = 15, scale = 2)
  private BigDecimal totalDeposited;

  @Column(name = "total_spent", precision = 15, scale = 2)
  private BigDecimal totalSpent;
}
