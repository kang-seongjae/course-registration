package com.example.courseregistration.controller;

import com.example.courseregistration.dto.EnrollmentRequest;
import com.example.courseregistration.dto.EnrollmentResponse;
import com.example.courseregistration.service.EnrollmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 수강 신청 관련 HTTP 요청을 받는 컨트롤러.
 */
@RestController
public class EnrollmentController {

    private final EnrollmentService enrollmentService;

    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    /** GET /api/students/{studentId}/enrollments : 내 신청 목록 조회 */
    @GetMapping("/api/students/{studentId}/enrollments")
    public List<EnrollmentResponse> getMyEnrollments(@PathVariable Long studentId) {
        return enrollmentService.getMyEnrollments(studentId).stream()
                .map(EnrollmentResponse::from)
                .toList();
    }

    /** POST /api/enrollments : 수강 신청 (본문: {"studentId": 1, "courseId": 3}) */
    @PostMapping("/api/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse enroll(@RequestBody EnrollmentRequest request) {
        return EnrollmentResponse.from(
                enrollmentService.enroll(request.studentId(), request.courseId()));
    }

    /** DELETE /api/enrollments/{enrollmentId}?studentId=1 : 수강 신청 취소 */
    @DeleteMapping("/api/enrollments/{enrollmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long enrollmentId, @RequestParam Long studentId) {
        enrollmentService.cancel(studentId, enrollmentId);
    }
}
