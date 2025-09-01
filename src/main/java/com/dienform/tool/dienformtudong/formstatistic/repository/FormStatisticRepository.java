package com.dienform.tool.dienformtudong.formstatistic.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.formstatistic.entity.FormStatistic;

@Repository
public interface FormStatisticRepository extends JpaRepository<FormStatistic, UUID> {

    /**
     * Tìm FormStatistic theo form ID
     */
    Optional<FormStatistic> findByFormId(UUID formId);

    /**
     * Xóa FormStatistic theo form
     */
    void deleteByForm(Form form);
}