package com.example.courseregistration.repository;

import com.example.courseregistration.domain.Student;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 학생 테이블에 접근하는 리포지토리.
 */
public interface StudentRepository extends JpaRepository<Student, Long> {
}
