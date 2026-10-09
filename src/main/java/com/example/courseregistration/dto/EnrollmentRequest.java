package com.example.courseregistration.dto;

/**
 * 수강 신청 요청 본문(JSON). 예: {"studentId": 1, "courseId": 3}
 */
public record EnrollmentRequest(Long studentId, Long courseId) {
}
