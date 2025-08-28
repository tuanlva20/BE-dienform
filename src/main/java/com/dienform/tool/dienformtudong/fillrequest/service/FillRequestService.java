package com.dienform.tool.dienformtudong.fillrequest.service;

import java.util.UUID;
import com.dienform.tool.dienformtudong.datamapping.dto.request.DataFillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.request.FillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.BatchProgressResponse;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;

public interface FillRequestService {
    FillRequestResponse createFillRequest(UUID formId, FillRequestDTO fillRequestDTO);

    FillRequestResponse getFillRequestById(UUID id);

    void deleteFillRequest(UUID id);

    FillRequestResponse createDataFillRequest(DataFillRequestDTO dataFillRequestDTO);

    BatchProgressResponse getBatchProgress(UUID requestId);
}
