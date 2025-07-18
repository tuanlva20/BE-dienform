package com.dienform.tool.dienformtudong.form.dto.request;

import com.dienform.tool.dienformtudong.form.entity.Form;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.io.Serializable;

/**
 * DTO for {@link Form}
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class FormRequest implements Serializable {
  String name;
  String editLink;
}
