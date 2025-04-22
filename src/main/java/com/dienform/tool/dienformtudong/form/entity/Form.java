package com.dienform.tool.dienformtudong.form.entity;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import com.dienform.common.entity.AuditEntity;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.form.enums.FormStatusEnum;
import com.dienform.tool.dienformtudong.formstatistic.entity.FormStatistic;
import com.dienform.tool.dienformtudong.question.entity.Question;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "form")
public class Form extends AuditEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "name", insertable = false, updatable = false, nullable = false)
    private String name;

    @Column(name = "edit_link", nullable = false)
    private String editLink;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.ORDINAL)
    private FormStatusEnum status;

    @OneToMany(mappedBy = "form", fetch = FetchType.LAZY)
    private Set<FillRequest> fillRequests = new LinkedHashSet<>();

    @OneToOne(mappedBy = "form")
    private FormStatistic formStatistic;

    @OneToMany(mappedBy = "form")
    private Set<Question> questions = new LinkedHashSet<>();
}
