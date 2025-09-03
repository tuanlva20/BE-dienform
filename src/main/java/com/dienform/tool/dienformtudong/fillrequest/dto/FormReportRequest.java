package com.dienform.tool.dienformtudong.fillrequest.dto;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;
import com.dienform.tool.dienformtudong.form.enums.FormStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormReportRequest {

  @Builder.Default
  private String searchTerm = ""; // Tìm kiếm theo tên, loại, trạng thái

  @Builder.Default
  private FillRequestStatusEnum status = null; // Filter theo trạng thái

  @Builder.Default
  private FormStatusEnum formStatus = null; // Filter theo trạng thái form

  @Builder.Default
  private String formType = ""; // Auto/Manual

  @Builder.Default
  @DateTimeFormat(pattern = "yyyy-MM-dd")
  private LocalDate startDate = null;

  @Builder.Default
  @DateTimeFormat(pattern = "yyyy-MM-dd")
  private LocalDate endDate = null;

  @Builder.Default
  private Integer page = 0;

  @Builder.Default
  private Integer size = 10;

  @Builder.Default
  private String sortBy = "createdAt";

  @Builder.Default
  private String sortDirection = "desc"; // asc, desc

  private UUID userId; // ID của user hiện tại để filter
}
