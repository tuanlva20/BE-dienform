package com.dienform.tool.dienformtudong.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionOptionResponse {
    private UUID id;
    private String text;
    private String value;
    private int position;
}