package com.dienform.tool.dienformtudong.form.dto.request;

import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.enums.FormStatusEnum;
import com.dienform.tool.dienformtudong.question.dto.request.QuestionDetailRequest;
import lombok.Value;

import java.io.Serializable;
import java.util.Set;
import java.util.UUID;

/**
 * DTO for {@link Form}
 */
@Value
public class FormDetailRequest implements Serializable {
  UUID id;
  String name;
  String editLink;
  FormStatusEnum status;
  Set<QuestionDetailRequest> questions;
}
