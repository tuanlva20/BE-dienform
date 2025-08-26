package com.dienform.tool.dienformtudong.payment.controller;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.model.ResponseModel;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.tool.dienformtudong.payment.service.UserBalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/payments/balance")
@RequiredArgsConstructor
@Slf4j
public class UserBalanceController {

  private final UserBalanceService userBalanceService;
  private final CurrentUserUtil currentUserUtil;

  @GetMapping
  public ResponseEntity<ResponseModel<BigDecimal>> getBalance() {
    try {
      String userId = currentUserUtil.requireCurrentUserId().toString();
      BigDecimal balance = userBalanceService.getBalance(userId);

      return ResponseEntity.ok(ResponseModel.success(balance, HttpStatus.OK));
    } catch (Exception e) {
      log.error("Error getting user balance", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
          ResponseModel.error("Failed to get balance: " + e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }
}
