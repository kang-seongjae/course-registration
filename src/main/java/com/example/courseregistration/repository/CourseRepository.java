package com.example.courseregistration.repository;

import com.example.courseregistration.domain.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 강의 테이블에 접근하는 리포지토리.
 * JpaRepository 를 상속하면 findAll(), findById(), save() 등이 자동으로 제공된다.
 *
 * 4단계-2: 비관적 락(SELECT ... FOR UPDATE) 대신 "조건부 UPDATE" 를 사용한다.
 * 정원 검사와 인원 증가를 UPDATE 한 문장에 담으면, DB 가 그 문장을 실행하는 동안만
 * 강의 행을 잠그므로 락을 쥐는 시간이 훨씬 짧아진다.
 */
public interface CourseRepository extends JpaRepository<Course, Long> {

    /**
     * 정원이 남아 있을 때만 신청 인원을 1 늘린다. (정원 검사 + 증가를 한 문장으로)
     *
     * 실행되는 SQL: update courses set enrolled_count = enrolled_count + 1
     *               where id = ? and enrolled_count < capacity
     *
     * 반환값은 바뀐 행 수다. 1 이면 신청 성공, 0 이면 정원이 찼거나 강의가 없는 것이다.
     * enrolled_count + 1 은 DB 가 "지금 저장된 값" 에 더하므로 다른 요청의 증가분을 덮어쓰지 않는다.
     */
    @Modifying
    @Query("update Course c set c.enrolledCount = c.enrolledCount + 1 "
            + "where c.id = :id and c.enrolledCount < c.capacity")
    int increaseEnrolledCountIfNotFull(@Param("id") Long id);

    /**
     * 신청 인원을 1 줄인다. (0 아래로는 내려가지 않음)
     */
    @Modifying
    @Query("update Course c set c.enrolledCount = c.enrolledCount - 1 "
            + "where c.id = :id and c.enrolledCount > 0")
    int decreaseEnrolledCount(@Param("id") Long id);
}
