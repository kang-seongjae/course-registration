package com.example.courseregistration.service;

import com.example.courseregistration.domain.Course;
import com.example.courseregistration.repository.CourseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 동시성 문제를 눈으로 확인하기 위한 테스트.
 *
 * 정원 30명인 강의(CS101)에 서로 다른 학생 100명이 "같은 순간"에 신청한다.
 * 실제 MySQL(docker compose)에 대해 실행되므로 먼저 `docker compose up -d` 가 필요하다.
 *
 * 동시성 처리가 없으면 실패하고, 비관적 락을 적용하면 통과한다.
 */
@SpringBootTest
class EnrollmentConcurrencyTest {

    private static final int THREAD_COUNT = 100;   // 동시에 신청하는 학생 수
    private static final String COURSE_CODE = "CS101"; // 정원 30명

    @Autowired
    EnrollmentService enrollmentService;

    @Autowired
    CourseRepository courseRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Course course;

    @BeforeEach
    void resetCourse() {
        course = courseRepository.findAll().stream()
                .filter(c -> c.getCode().equals(COURSE_CODE))
                .findFirst()
                .orElseThrow();

        // 이전 실행의 흔적을 지우고 깨끗한 상태에서 시작한다
        jdbcTemplate.update("delete from enrollments where course_id = ?", course.getId());
        jdbcTemplate.update("update courses set enrolled_count = 0 where id = ?", course.getId());
    }

    @Test
    void 학생_100명이_정원_30명_강의에_동시에_신청한다() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT); // 모든 스레드가 준비됐는지
        CountDownLatch start = new CountDownLatch(1);            // 출발 신호 (총성)
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);  // 모든 스레드가 끝났는지

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();  // 규칙에 의해 거절됨 (정원 초과 등)
        AtomicInteger error = new AtomicInteger();     // 그 밖의 예외 (DB 오류 등)
        List<String> errorMessages = new ArrayList<>();

        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= THREAD_COUNT; i++) {
            long studentId = i; // 학생 1 ~ 100, 모두 다른 학생
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    start.await(); // 출발 신호가 올 때까지 대기
                    enrollmentService.enroll(studentId, course.getId());
                    success.incrementAndGet();
                } catch (EnrollmentException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    error.incrementAndGet();
                    synchronized (errorMessages) {
                        errorMessages.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                } finally {
                    done.countDown();
                }
            });
            threads.add(t);
            t.start();
        }

        ready.await();      // 100개 스레드가 모두 대기 상태에 들어갈 때까지 기다린 뒤
        start.countDown();  // 동시에 출발!
        done.await();       // 모두 끝날 때까지 대기

        // ---- 결과 확인 ----
        Course after = courseRepository.findById(course.getId()).orElseThrow();
        Integer actualRows = jdbcTemplate.queryForObject(
                "select count(*) from enrollments where course_id = ?", Integer.class, course.getId());

        System.out.println("==================== 동시 신청 테스트 결과 ====================");
        System.out.println("강의              : " + after.getCode() + " " + after.getName() + " (정원 " + after.getCapacity() + ")");
        System.out.println("동시 신청 학생 수 : " + THREAD_COUNT);
        System.out.println("신청 성공 수      : " + success.get());
        System.out.println("신청 거절 수      : " + rejected.get() + "  (정원 초과/중복 등 규칙에 의한 거절)");
        System.out.println("기타 오류 수      : " + error.get());
        System.out.println("강의의 신청 인원  : " + after.getEnrolledCount() + "  (courses.enrolled_count)");
        System.out.println("실제 신청 내역 수 : " + actualRows + "  (enrollments 테이블 행 수)");
        if (!errorMessages.isEmpty()) {
            errorMessages.stream().distinct().limit(5)
                    .forEach(m -> System.out.println("  오류 예시: " + m));
        }
        System.out.println("RESULT_LINE|" + success.get() + "|" + rejected.get() + "|" + error.get()
                + "|" + after.getEnrolledCount() + "|" + actualRows);
        System.out.println("================================================================");

        // 정상이라면 정원(30)만큼만 성공해야 한다. 동시성 처리가 없으면 아래 검증이 실패한다.
        assertEquals(after.getCapacity(), actualRows, "실제 신청 내역 수가 정원과 달라야 하면 안 된다");
        assertEquals(after.getCapacity(), after.getEnrolledCount(), "강의의 신청 인원이 정원과 달라야 하면 안 된다");
    }
}
