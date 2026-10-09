package com.example.courseregistration.repository;

import com.example.courseregistration.domain.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
