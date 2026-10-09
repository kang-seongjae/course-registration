package com.example.courseregistration.controller;

import com.example.courseregistration.dto.ErrorResponse;
import com.example.courseregistration.service.EnrollmentException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 컨트롤러에서 발생한 예외를 한 곳에서 처리해 JSON 오류 응답으로 바꿔 준다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 수강 신청 규칙 위반 -> 400 Bad Request + {"message": "..."} */
    @ExceptionHandler(EnrollmentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleEnrollmentException(EnrollmentException e) {
        return new ErrorResponse(e.getMessage());
    }
}
