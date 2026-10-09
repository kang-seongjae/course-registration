package com.example.courseregistration.controller;

import com.example.courseregistration.dto.CourseResponse;
import com.example.courseregistration.service.EnrollmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 강의 관련 HTTP 요청을 받는 컨트롤러.
 */
@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final EnrollmentService enrollmentService;

    public CourseController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    /** GET /api/courses : 강의 목록 조회 */
    @GetMapping
    public List<CourseResponse> getCourses() {
        return enrollmentService.getCourses().stream()
                .map(CourseResponse::from)
                .toList();
    }
}
