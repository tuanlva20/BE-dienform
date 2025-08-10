package com.dienform.common.validator;

import java.beans.PropertyDescriptor;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class FieldMatchValidator implements ConstraintValidator<FieldMatch, Object> {
  private String firstFieldName;
  private String secondFieldName;
  private String message;

  @Override
  public void initialize(FieldMatch constraintAnnotation) {
    this.firstFieldName = constraintAnnotation.first();
    this.secondFieldName = constraintAnnotation.second();
    this.message = constraintAnnotation.message();
  }

  @Override
  public boolean isValid(Object value, ConstraintValidatorContext context) {
    try {
      Object first = readProperty(value, firstFieldName);
      Object second = readProperty(value, secondFieldName);
      boolean matches =
          (first == null && second == null) || (first != null && first.equals(second));
      if (!matches) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(secondFieldName)
            .addConstraintViolation();
      }
      return matches;
    } catch (Exception e) {
      return false;
    }
  }

  private Object readProperty(Object bean, String fieldName) throws Exception {
    PropertyDescriptor pd = new PropertyDescriptor(fieldName, bean.getClass());
    return pd.getReadMethod().invoke(bean);
  }
}


