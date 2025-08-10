package com.dienform.tool.dienformtudong.form.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.dienform.common.entity.AuditEntity;
import com.dienform.common.entity.User;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.form.enums.FormStatusEnum;
import com.dienform.tool.dienformtudong.formstatistic.entity.FormStatistic;
import com.dienform.tool.dienformtudong.question.entity.Question;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
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

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "created_by", referencedColumnName = "id")
  private User createdBy;
}
