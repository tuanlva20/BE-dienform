package com.dienform.tool.dienformtudong.form.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.dienform.tool.dienformtudong.formstatistic.dto.response.FormStatisticResponse;
import com.dienform.tool.dienformtudong.question.dto.response.QuestionResponse;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormDetailResponse {
    private UUID id;
    private String name;
    private String editLink;
    private LocalDateTime createdAt;
    private String status;
    private FormStatisticResponse statistics;
    private List<QuestionResponse> questions;
}
