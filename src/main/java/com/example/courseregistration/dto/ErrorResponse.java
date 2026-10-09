package com.example.courseregistration.dto;

/**
 * 오류가 발생했을 때 화면에 전달하는 응답 객체. 예: {"message": "정원이 가득 찼습니다"}
 */
public record ErrorResponse(String message) {
}
