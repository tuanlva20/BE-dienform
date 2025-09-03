package com.dienform.tool.dienformtudong.formstatistic.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum;

@Repository
public interface SurveyStatisticsRepository extends JpaRepository<FillRequest, java.util.UUID> {

        /**
         * Đếm tổng số khảo sát theo trạng thái
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE fr.status = :status")
        Long countByStatus(@Param("status") FillRequestStatusEnum status);

        /**
         * Đếm tổng số khảo sát theo trạng thái và userId
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE fr.status = :status AND fr.form.createdBy.id = :userId")
        Long countByStatusAndUserId(@Param("status") FillRequestStatusEnum status,
                        @Param("userId") UUID userId);

        /**
         * Đếm số khảo sát được tạo trong ngày hôm nay
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE DATE(fr.createdAt) = CURRENT_DATE")
        Long countNewSurveysToday();

        /**
         * Đếm số khảo sát được tạo trong ngày hôm nay theo userId
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE DATE(fr.createdAt) = CURRENT_DATE AND fr.form.createdBy.id = :userId")
        Long countNewSurveysTodayByUserId(@Param("userId") UUID userId);

        /**
         * Đếm số khảo sát hoàn thành trong tuần này
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE fr.status = 'COMPLETED' "
                        + "AND fr.updatedAt >= :startOfWeek")
        Long countCompletedSurveysThisWeek(@Param("startOfWeek") LocalDateTime startOfWeek);

        /**
         * Đếm số khảo sát hoàn thành trong tuần này theo userId
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE fr.status = 'COMPLETED' "
                        + "AND fr.updatedAt >= :startOfWeek AND fr.form.createdBy.id = :userId")
        Long countCompletedSurveysThisWeekByUserId(@Param("startOfWeek") LocalDateTime startOfWeek,
                        @Param("userId") UUID userId);

        /**
         * Đếm số khảo sát thất bại cần xem lại (FAILED status)
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE fr.status = 'FAILED'")
        Long countFailedSurveysNeedReview();

        /**
         * Đếm số khảo sát thất bại cần xem lại theo userId
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE fr.status = 'FAILED' AND fr.form.createdBy.id = :userId")
        Long countFailedSurveysNeedReviewByUserId(@Param("userId") UUID userId);

        /**
         * Đếm tổng số khảo sát đã chạy thành công
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE fr.status = 'COMPLETED'")
        Long countTotalSurveysRunSuccessfully();

        /**
         * Đếm tổng số khảo sát đã chạy thành công theo userId
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE fr.status = 'COMPLETED' AND fr.form.createdBy.id = :userId")
        Long countTotalSurveysRunSuccessfullyByUserId(@Param("userId") UUID userId);

        /**
         * Lấy thống kê theo trạng thái với thông tin chi tiết
         */
        @Query("SELECT fr.status, COUNT(fr), SUM(fr.completedSurvey), SUM(fr.failedSurvey) "
                        + "FROM FillRequest fr GROUP BY fr.status")
        List<Object[]> getStatisticsByStatus();

        /**
         * Lấy thống kê theo khoảng thời gian
         */
        @Query("SELECT COUNT(fr), SUM(fr.completedSurvey), SUM(fr.failedSurvey) "
                        + "FROM FillRequest fr WHERE fr.createdAt BETWEEN :startDate AND :endDate")
        Object[] getStatisticsByDateRange(@Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate);

        /**
         * Lấy thống kê theo khoảng thời gian và userId
         */
        @Query("SELECT COUNT(fr), SUM(fr.completedSurvey), SUM(fr.failedSurvey) "
                        + "FROM FillRequest fr WHERE fr.createdAt BETWEEN :startDate AND :endDate "
                        + "AND fr.form.createdBy.id = :userId")
        Object[] getStatisticsByDateRangeAndUserId(@Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate, @Param("userId") UUID userId);

        /**
         * Lấy thống kê trend (so sánh với tuần trước)
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE fr.status = :status "
                        + "AND fr.createdAt BETWEEN :startDate AND :endDate")
        Long countByStatusAndDateRange(@Param("status") FillRequestStatusEnum status,
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate);

        /**
         * Lấy thống kê trend (so sánh với tuần trước) theo userId
         */
        @Query("SELECT COUNT(fr) FROM FillRequest fr WHERE fr.status = :status "
                        + "AND fr.createdAt BETWEEN :startDate AND :endDate "
                        + "AND fr.form.createdBy.id = :userId")
        Long countByStatusAndDateRangeAndUserId(@Param("status") FillRequestStatusEnum status,
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate, @Param("userId") UUID userId);
}
