package com.dienform.tool.dienformtudong.form.enums;

import lombok.Getter;

@Getter
public enum FormStatusEnum {
  CREATED, PROCESSING, COMPLETED;

  private final String label;

  FormStatusEnum() {
    this.label = this.name();
  }
}
