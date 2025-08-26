package com.dienform.tool.dienformtudong.payment.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.payment.entity.UserBalance;

@Repository
public interface UserBalanceRepository extends JpaRepository<UserBalance, UUID> {

  Optional<UserBalance> findByUserId(String userId);
}
