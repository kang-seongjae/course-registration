package com.example.courseregistration.dto;

import com.example.courseregistration.domain.Enrollment;

import java.time.LocalDateTime;

/**
 * 수강 신청 내역을 화면(JSON)으로 내보낼 때 사용하는 응답 객체.
 */
public record EnrollmentResponse(
        Long id,
        Long studentId,
        Long courseId,
        String courseCode,
        String courseName,
        String professor,
        LocalDateTime createdAt
) {
    public static EnrollmentResponse from(Enrollment enrollment) {
        return new EnrollmentResponse(
                enrollment.getId(),
                enrollment.getStudent().getId(),
                enrollment.getCourse().getId(),
                enrollment.getCourse().getCode(),
                enrollment.getCourse().getName(),
                enrollment.getCourse().getProfessor(),
                enrollment.getCreatedAt()
        );
    }
}
