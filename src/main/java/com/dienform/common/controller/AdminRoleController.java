package com.dienform.common.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.common.model.ResponseModel;
import com.dienform.common.service.RoleService;
import jakarta.validation.constraints.Email;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
@Slf4j
public class AdminRoleController {

  @Data
  public static class EmailBody {
    @Email
    private String email;
  }

  private final RoleService roleService;

  @PostMapping("/grant-admin")
  public ResponseEntity<ResponseModel<String>> grantAdmin(@RequestBody EmailBody body) {
    try {
      roleService.grantAdminByEmail(body.getEmail());
      return ResponseEntity.ok(ResponseModel.success("Granted ADMIN", HttpStatus.OK));
    } catch (Exception e) {
      log.error("Grant admin failed", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ResponseModel.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }

  @PostMapping("/revoke-admin")
  public ResponseEntity<ResponseModel<String>> revokeAdmin(@RequestBody EmailBody body) {
    try {
      roleService.revokeAdminByEmail(body.getEmail());
      return ResponseEntity.ok(ResponseModel.success("Revoked ADMIN", HttpStatus.OK));
    } catch (Exception e) {
      log.error("Revoke admin failed", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ResponseModel.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    }
  }
}


