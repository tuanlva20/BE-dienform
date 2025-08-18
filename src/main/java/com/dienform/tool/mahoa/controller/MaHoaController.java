package com.dienform.tool.mahoa.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dienform.tool.mahoa.dto.request.EncodeDataRequest;
import com.dienform.tool.mahoa.service.DataEncodingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/ma-hoa")
@RequiredArgsConstructor
@Validated
@Slf4j
public class MaHoaController {

  private final DataEncodingService dataEncodingService;

  @PostMapping
  public ResponseEntity<?> encode(@Valid @RequestBody EncodeDataRequest request) {
    try {
      var result = dataEncodingService.encodeSheetData(request);

      // If there are validation errors during encoding, return structured error response
      if (result != null && result.hasErrors()) {
        var details = new java.util.HashMap<String, Object>();
        details.put("rowErrors", result.getRowErrors());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(com.dienform.common.model.ResponseModel.error(
                "Dữ liệu không hợp lệ: có lỗi khi mã hóa một số ô", HttpStatus.BAD_REQUEST,
                com.dienform.common.model.ErrorCode.ENCODING_DATA_ERROR, details));
      }

      String downloadName = "ma-hoa-data.xlsx";
      String encodedFileName =
          URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
      headers.set(HttpHeaders.CONTENT_DISPOSITION,
          "attachment; filename*=UTF-8''" + encodedFileName);
      // If there are row-level errors, include them in headers for FE to fetch if needed
      return new ResponseEntity<>(result.getExcelBytes(), headers, HttpStatus.CREATED);
    } catch (IllegalArgumentException e) {
      log.warn("Bad request for encoding: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
          com.dienform.common.model.ResponseModel.error(e.getMessage(), HttpStatus.BAD_REQUEST));
    } catch (Exception e) {
      log.error("Unexpected error encoding sheet", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(com.dienform.common.model.ResponseModel.error("Internal server error",
              HttpStatus.INTERNAL_SERVER_ERROR));
    }
  }
}


