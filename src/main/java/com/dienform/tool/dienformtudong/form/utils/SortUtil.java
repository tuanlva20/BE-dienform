package com.dienform.tool.dienformtudong.form.utils;

import java.util.Comparator;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import lombok.experimental.UtilityClass;

@UtilityClass
public class SortUtil {
  public static Form sortByPosition(Form form) {
    form.getQuestions().sort(Comparator.comparing(Question::getPosition));
    form.getQuestions().forEach(
        question -> question.getOptions().sort(Comparator.comparing(QuestionOption::getPosition)));
    return form;
  }
  public static Form sortFillRequestsByCreatedAt(Form form) {
    form.getFillRequests().sort(Comparator.comparing(FillRequest::getCreatedAt, Comparator.reverseOrder()));
    return form;
  }
}
