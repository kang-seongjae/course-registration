package com.example.courseregistration.dto;

import com.example.courseregistration.domain.Course;

/**
 * 강의 정보를 화면(JSON)으로 내보낼 때 사용하는 응답 객체.
 * 엔티티를 그대로 노출하지 않고 필요한 값만 담는다.
 */
public record CourseResponse(
        Long id,
        String code,
        String name,
        String professor,
        int capacity,
        int enrolledCount,
        boolean full
) {
    public static CourseResponse from(Course course) {
        return new CourseResponse(
                course.getId(),
                course.getCode(),
                course.getName(),
                course.getProfessor(),
                course.getCapacity(),
                course.getEnrolledCount(),
                course.isFull()
        );
    }
}
