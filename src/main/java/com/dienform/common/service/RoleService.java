package com.dienform.common.service;

import com.dienform.common.entity.User;

public interface RoleService {
  void grantAdminByEmail(String email);

  void revokeAdminByEmail(String email);

  String getPrimaryRole(User user);

  void ensureDefaultUserRole(User user);
}


