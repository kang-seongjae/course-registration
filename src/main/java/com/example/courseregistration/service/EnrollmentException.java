package com.example.courseregistration.service;

/**
 * 수강 신청 규칙에 어긋났을 때 던지는 예외.
 * (예: 정원 초과, 중복 신청, 존재하지 않는 학생/강의)
 */
public class EnrollmentException extends RuntimeException {

    public EnrollmentException(String message) {
        super(message);
    }
}
