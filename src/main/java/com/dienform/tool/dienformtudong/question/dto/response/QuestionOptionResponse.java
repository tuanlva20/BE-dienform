package com.dienform.tool.dienformtudong.question.dto.response;

import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionOptionResponse {
    private UUID id;
    private String text;
    private String value; 
    private int position;
    private boolean isRow;
    private List<String> columnOptions;
}
