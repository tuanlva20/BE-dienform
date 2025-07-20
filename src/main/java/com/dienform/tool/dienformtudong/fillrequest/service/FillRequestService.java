package com.dienform.tool.dienformtudong.fillrequest.service;

import java.util.Map;
import java.util.UUID;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.request.FillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;

public interface FillRequestService {
    FillRequestResponse createFillRequest(UUID formId, FillRequestDTO fillRequestDTO);

    FillRequestResponse getFillRequestById(UUID id);

    Map<String, Object> startFillRequest(UUID id);

    void deleteFillRequest(UUID id);

    FillRequestResponse createDataFillRequest(DataFillRequestDTO dataFillRequestDTO);
}
