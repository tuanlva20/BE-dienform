package com.dienform.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentOrderSearchRequest {
  private Integer page = 0;
  private Integer size = 10;
  private String sortBy = "createdAt";
  private String sortDirection = "DESC";
  private String paymentType;
  private String status;
  private String userName;
  private String userEmail;
  private Boolean isPromotional;
  private Boolean isReported;
  private String fromDate;
  private String toDate;
}
