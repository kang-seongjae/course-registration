package com.example.courseregistration.repository;

import com.example.courseregistration.domain.Course;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 강의 테이블에 접근하는 리포지토리.
 * JpaRepository 를 상속하면 findAll(), findById(), save() 등이 자동으로 제공된다.
 */
public interface CourseRepository extends JpaRepository<Course, Long> {

    /**
     * 강의 한 건을 "비관적 쓰기 락"을 걸고 조회한다.
     * 실제로는 SELECT ... FOR UPDATE 가 실행되어, 이 트랜잭션이 끝날 때까지
     * 다른 트랜잭션은 같은 강의 행을 FOR UPDATE 로 읽거나 수정할 수 없고 줄을 서서 기다린다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Course c where c.id = :id")
    Optional<Course> findByIdForUpdate(@Param("id") Long id);
}
