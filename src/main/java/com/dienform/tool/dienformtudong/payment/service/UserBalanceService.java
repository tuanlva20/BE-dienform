package com.dienform.tool.dienformtudong.payment.service;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.payment.entity.UserBalance;
import com.dienform.tool.dienformtudong.payment.exception.PaymentException;
import com.dienform.tool.dienformtudong.payment.repository.UserBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserBalanceService {

  private final UserBalanceRepository userBalanceRepository;

  @Transactional
  public void addBalance(String userId, BigDecimal amount) {
    try {
      UserBalance userBalance = userBalanceRepository.findByUserId(userId)
          .orElseGet(() -> UserBalance.builder().userId(userId).balance(BigDecimal.ZERO)
              .totalDeposited(BigDecimal.ZERO).totalSpent(BigDecimal.ZERO).build());

      BigDecimal currentBalance = userBalance.getBalance();
      BigDecimal newBalance = currentBalance.add(amount);

      userBalance.setBalance(newBalance);
      userBalance.setTotalDeposited(userBalance.getTotalDeposited().add(amount));
      userBalanceRepository.save(userBalance);

      log.info("Added balance {} to user {}. New balance: {}", amount, userId, newBalance);
    } catch (Exception e) {
      log.error("Error adding balance to user: {}", userId, e);
      throw new PaymentException("Failed to add balance", e);
    }
  }

  @Transactional(readOnly = true)
  public BigDecimal getBalance(String userId) {
    try {
      UserBalance userBalance = userBalanceRepository.findByUserId(userId)
          .orElse(UserBalance.builder().userId(userId).balance(BigDecimal.ZERO)
              .totalDeposited(BigDecimal.ZERO).totalSpent(BigDecimal.ZERO).build());

      return userBalance.getBalance();
    } catch (Exception e) {
      log.error("Error getting balance for user: {}", userId, e);
      throw new PaymentException("Failed to get balance", e);
    }
  }
}
