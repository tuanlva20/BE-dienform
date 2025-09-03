package com.dienform.tool.dienformtudong.fillrequest.service;

import com.dienform.tool.dienformtudong.fillrequest.dto.FormReportPageResponse;
import com.dienform.tool.dienformtudong.fillrequest.dto.FormReportRequest;
import com.dienform.tool.dienformtudong.fillrequest.dto.FormReportSummaryResponse;

public interface FormReportService {

  /**
   * Get paginated form report with filtering
   */
  FormReportPageResponse getFormReport(FormReportRequest request);

  /**
   * Get summary statistics for form report
   */
  FormReportSummaryResponse getFormReportSummary(FormReportRequest request);
}
