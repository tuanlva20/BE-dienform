package com.dienform.common.dto.auth;

import com.dienform.common.validator.FieldMatch;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldMatch(first = "password", second = "confirmPassword",
    message = "Xác nhận mật khẩu không khớp")
public class RegisterRequest {
  private String name;

  @NotBlank(message = "Email không được để trống")
  @Email(message = "Email không hợp lệ")
  private String email;

  @NotBlank(message = "Mật khẩu không được để trống")
  @Size(min = 6, message = "Mật khẩu tối thiểu 6 ký tự")
  private String password;

  @NotBlank(message = "Xác nhận mật khẩu không được để trống")
  private String confirmPassword;
}


