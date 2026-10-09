package com.example.courseregistration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 강의 엔티티. courses 테이블의 한 행(row)에 해당한다.
 */
@Entity
@Table(name = "courses")
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 강의 코드 (예: CS101) */
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    /** 강의명 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 담당 교수 */
    @Column(nullable = false, length = 50)
    private String professor;

    /** 정원 */
    @Column(nullable = false)
    private int capacity;

    /** 현재 신청 인원 */
    @Column(nullable = false)
    private int enrolledCount;

    protected Course() {
        // JPA 가 객체를 만들 때 사용하는 기본 생성자
    }

    public Course(String code, String name, String professor, int capacity) {
        this.code = code;
        this.name = name;
        this.professor = professor;
        this.capacity = capacity;
        this.enrolledCount = 0;
    }

    /** 정원이 찼는지 확인 */
    public boolean isFull() {
        return enrolledCount >= capacity;
    }

    /** 신청 인원 1 증가 */
    public void increaseEnrolledCount() {
        this.enrolledCount++;
    }

    /** 신청 인원 1 감소 (0 아래로는 내려가지 않음) */
    public void decreaseEnrolledCount() {
        if (this.enrolledCount > 0) {
            this.enrolledCount--;
        }
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getProfessor() {
        return professor;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getEnrolledCount() {
        return enrolledCount;
    }
}
