package com.dienform.tool.dienformtudong.form.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;
import com.dienform.tool.dienformtudong.formstatistic.dto.response.FormStatisticResponse;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormResponse implements Serializable {
    private UUID id;
    private String name;
    private String status;
    private LocalDateTime createdAt;
    private String editLink;
}
