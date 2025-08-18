package com.dienform.tool.mahoa.service;

import com.dienform.tool.mahoa.dto.request.EncodeDataRequest;
import com.dienform.tool.mahoa.service.model.EncodeResult;

public interface DataEncodingService {
  EncodeResult encodeSheetData(EncodeDataRequest request) throws Exception;
}


