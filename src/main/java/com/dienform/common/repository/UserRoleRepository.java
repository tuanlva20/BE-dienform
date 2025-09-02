package com.dienform.common.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.dienform.common.entity.UserRole;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

  @Query("SELECT ur FROM UserRole ur JOIN FETCH ur.role WHERE ur.user.id = :userId")
  List<UserRole> findAllByUserIdFetchRole(@Param("userId") UUID userId);

  @Modifying
  @Query("DELETE FROM UserRole ur WHERE ur.user.id = :userId AND ur.role.id = :roleId")
  void deleteByUserIdAndRoleId(@Param("userId") UUID userId, @Param("roleId") UUID roleId);

  @Modifying
  @Query("DELETE FROM UserRole ur WHERE ur.user.id = :userId")
  void deleteAllByUserId(@Param("userId") UUID userId);
}


