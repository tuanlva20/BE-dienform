package com.dienform.tool.dienformtudong.formstatistic.repository;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.payment.entity.PaymentOrder;
import com.dienform.tool.dienformtudong.payment.enums.PaymentStatus;

@Repository
public interface OrderStatisticsRepository extends JpaRepository<PaymentOrder, java.util.UUID> {

  /**
   * Lấy tất cả payment orders theo userId với pagination
   */
  Page<PaymentOrder> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

  /**
   * Lấy tất cả payment orders theo userId (không pagination - cho backward compatibility)
   */
  List<PaymentOrder> findByUserIdOrderByCreatedAtDesc(String userId);

  /**
   * Lấy orders theo userId và status với pagination
   */
  Page<PaymentOrder> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, PaymentStatus status,
      Pageable pageable);

  /**
   * Lấy orders theo userId và status (không pagination - cho backward compatibility)
   */
  List<PaymentOrder> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, PaymentStatus status);

  /**
   * Đếm tổng số orders theo userId
   */
  @Query("SELECT COUNT(p) FROM PaymentOrder p WHERE p.userId = :userId")
  Long countByUserId(@Param("userId") String userId);

  /**
   * Tính tổng số tiền theo userId
   */
  @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentOrder p WHERE p.userId = :userId")
  BigDecimal sumAmountByUserId(@Param("userId") String userId);

  /**
   * Tính tổng số tiền đã hoàn thành theo userId
   */
  @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentOrder p WHERE p.userId = :userId AND p.status = 'COMPLETED'")
  BigDecimal sumCompletedAmountByUserId(@Param("userId") String userId);

  /**
   * Đếm số orders theo trạng thái và userId
   */
  @Query("SELECT COUNT(p) FROM PaymentOrder p WHERE p.userId = :userId AND p.status = :status")
  Long countByUserIdAndStatus(@Param("userId") String userId,
      @Param("status") PaymentStatus status);

  /**
   * Lấy thống kê tổng quan theo userId
   */
  @Query("SELECT p.status, COUNT(p), SUM(p.amount) FROM PaymentOrder p WHERE p.userId = :userId GROUP BY p.status")
  List<Object[]> getStatisticsByUserId(@Param("userId") String userId);
}
