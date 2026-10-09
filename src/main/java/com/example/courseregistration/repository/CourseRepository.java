package com.example.courseregistration.repository;

import com.example.courseregistration.domain.Course;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 강의 테이블에 접근하는 리포지토리.
 * JpaRepository 를 상속하면 findAll(), findById(), save() 등이 자동으로 제공된다.
 */
public interface CourseRepository extends JpaRepository<Course, Long> {
}
