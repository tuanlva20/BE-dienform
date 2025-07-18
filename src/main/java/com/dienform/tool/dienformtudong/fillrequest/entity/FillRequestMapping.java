package com.dienform.tool.dienformtudong.fillrequest.entity;

import java.util.UUID;
import com.dienform.common.entity.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fill_request_mapping")
public class FillRequestMapping extends AuditEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "fill_request_id", nullable = false)
  private UUID fillRequestId;

  @Column(name = "question_id", nullable = false)
  private UUID questionId;

  @Column(name = "column_name", nullable = false, length = 500)
  private String columnName;

  @Column(name = "sheet_link", nullable = false, length = 1000)
  private String sheetLink;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "fill_request_id", insertable = false, updatable = false)
  private FillRequest fillRequest;
}
