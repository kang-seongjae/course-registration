package com.example.courseregistration.repository;

import com.example.courseregistration.domain.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 수강 신청 테이블에 접근하는 리포지토리.
 * 메서드 이름 규칙(existsBy..., findBy...)을 따르면 Spring Data JPA 가 SQL 을 자동으로 만들어 준다.
 */
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    /** 해당 학생이 해당 강의를 이미 신청했는지 확인 */
    boolean existsByStudentIdAndCourseId(Long studentId, Long courseId);

    /** 특정 학생의 신청 목록을 강의 정보와 함께 조회 (최근 신청 순) */
    @Query("select e from Enrollment e join fetch e.course where e.student.id = :studentId order by e.createdAt desc")
    List<Enrollment> findAllByStudentIdWithCourse(@Param("studentId") Long studentId);

    /**
     * 신청 내역 한 건을 지우고 지운 행 수를 돌려준다.
     * 0 이 돌아오면 그 사이에 다른 요청이 이미 지운 것이다. (같은 신청을 동시에 두 번 취소하는 경우)
     */
    @Modifying
    @Query("delete from Enrollment e where e.id = :id")
    int deleteByIdReturningCount(@Param("id") Long id);
}
