package com.dienform.tool.dienformtudong.formstatistic.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.formstatistic.entity.FormStatistic;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FormStatisticRepository extends JpaRepository<FormStatistic, UUID> {
    Optional<FormStatistic> findByFormId(UUID formId);

    void deleteByForm(Form form);
}