package com.dienform.tool.dienformtudong.form.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.form.entity.Form;
import java.util.UUID;

@Repository
public interface FormRepository extends JpaRepository<Form, UUID> {
    Page<Form> findByNameContainingIgnoreCase(String search, Pageable pageable);
}