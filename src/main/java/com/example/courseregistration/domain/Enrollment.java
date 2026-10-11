package com.example.courseregistration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 수강 신청 엔티티. "어떤 학생이 어떤 강의를 신청했는지"를 기록한다.
 * enrollments 테이블의 한 행에 해당한다.
 *
 * 4단계-2: (student_id, course_id) 유니크 제약을 건다.
 * 강의 행에 락을 걸지 않게 되면서 "중복 검사 → INSERT" 사이에 다른 요청이 끼어들 수 있으므로,
 * 같은 학생이 같은 강의를 두 번 신청하는 것은 DB 가 최종적으로 막는다.
 */
@Entity
@Table(name = "enrollments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_enrollments_student_course",
                columnNames = {"student_id", "course_id"}))
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id")
    private Course course;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected Enrollment() {
    }

    public Enrollment(Student student, Course course) {
        this.student = student;
        this.course = course;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Student getStudent() {
        return student;
    }

    public Course getCourse() {
        return course;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
