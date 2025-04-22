package com.dienform.tool.dienformtudong.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionResponse {
    private UUID id;
    private String title;
    private String description;
    private String type;
    private boolean required;
    private int position;
    private List<QuestionOptionResponse> options;
}