package com.dienform.tool.dienformtudong.form.entity;

import java.util.*;

import com.dienform.common.entity.AuditEntity;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.form.enums.FormStatusEnum;
import com.dienform.tool.dienformtudong.formstatistic.entity.FormStatistic;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.surveyexecution.entity.SurveyExecution;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "form")
public class Form extends AuditEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(name = "name")
  private String name;

  @Column(name = "edit_link")
  private String editLink;

  @Column(name = "status")
  @Enumerated(EnumType.ORDINAL)
  private FormStatusEnum status;

  @OneToMany(mappedBy = "form", fetch = FetchType.LAZY)
  private List<FillRequest> fillRequests = new ArrayList<>();

  @OneToMany(mappedBy = "form", fetch = FetchType.LAZY)
  private List<Question> questions = new ArrayList<>();

  @OneToOne(mappedBy = "form")
  private FormStatistic formStatistic;

//  @OneToMany(mappedBy = "form")
//  private Set<SurveyExecution> surveyExecutions = new LinkedHashSet<>();
}
