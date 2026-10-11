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
import java.util.function.IntToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 동시성 문제를 눈으로 확인하기 위한 테스트.
 *
 * 실제 MySQL(docker compose)에 대해 실행되므로 먼저 `docker compose up -d` 가 필요하다.
 * 매 테스트 전에 CS101(정원 30) 의 신청 내역을 지우고 인원을 0 으로 되돌린다.
 *
 * 테스트 1: 서로 다른 학생 100명이 같은 순간에 신청 → 정원(30)만큼만 성공해야 한다.
 * 테스트 2: 같은 학생이 같은 강의에 10번 동시에 신청 → 1건만 성공해야 한다.
 */
@SpringBootTest
class EnrollmentConcurrencyTest {

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
        // i번째 스레드는 학생 i 로 신청한다 (학생 1 ~ 100, 모두 다른 학생)
        Result r = enrollConcurrently("서로 다른 학생 100명 동시 신청", 100, i -> i);

        // 정상이라면 정원(30)만큼만 성공해야 한다. 동시성 처리가 없으면 아래 검증이 실패한다.
        assertEquals(course.getCapacity(), r.success, "신청 성공 수는 정원과 같아야 한다");
        assertEquals(100 - course.getCapacity(), r.rejected, "나머지는 모두 정원 초과로 거절되어야 한다");
        assertEquals(0, r.error, "규칙 거절 외의 오류(데드락 등)는 없어야 한다");
        assertEquals(course.getCapacity(), r.enrolledCount, "강의의 신청 인원은 정원과 같아야 한다");
        assertEquals(course.getCapacity(), r.actualRows, "실제 신청 내역 수는 정원과 같아야 한다");
    }

    @Test
    void 같은_학생이_같은_강의에_동시에_10번_신청하면_1건만_성공한다() throws InterruptedException {
        // 모든 스레드가 학생 1 로 신청한다
        Result r = enrollConcurrently("같은 학생 10번 동시 신청", 10, i -> 1L);

        assertEquals(1, r.success, "같은 학생의 신청은 1건만 성공해야 한다");
        assertEquals(9, r.rejected, "나머지는 중복 신청으로 거절되어야 한다");
        assertEquals(0, r.error, "규칙 거절 외의 오류는 없어야 한다");
        assertEquals(1, r.enrolledCount, "강의의 신청 인원은 1 이어야 한다");
        assertEquals(1, r.actualRows, "실제 신청 내역은 1행이어야 한다");
    }

    /** 한 번의 동시 신청 실행 결과 */
    record Result(int success, int rejected, int error, int enrolledCount, int actualRows) {
    }

    /**
     * threadCount 개의 스레드가 CountDownLatch 로 "같은 순간"에 CS101 을 신청한다.
     * studentIdOf 는 i번째 스레드(1부터)가 어떤 학생 ID 로 신청할지 정한다.
     */
    private Result enrollConcurrently(String title, int threadCount, IntToLongFunction studentIdOf)
            throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threadCount); // 모든 스레드가 준비됐는지
        CountDownLatch start = new CountDownLatch(1);           // 출발 신호 (총성)
        CountDownLatch done = new CountDownLatch(threadCount);  // 모든 스레드가 끝났는지

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();  // 규칙에 의해 거절됨 (정원 초과, 중복 등)
        AtomicInteger error = new AtomicInteger();     // 그 밖의 예외 (DB 오류 등)
        List<String> rejectMessages = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();

        for (int i = 1; i <= threadCount; i++) {
            long studentId = studentIdOf.applyAsLong(i);
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    start.await(); // 출발 신호가 올 때까지 대기
                    enrollmentService.enroll(studentId, course.getId());
                    success.incrementAndGet();
                } catch (EnrollmentException e) {
                    rejected.incrementAndGet();
                    synchronized (rejectMessages) {
                        rejectMessages.add(e.getMessage());
                    }
                } catch (Exception e) {
                    error.incrementAndGet();
                    synchronized (errorMessages) {
                        errorMessages.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }

        ready.await();      // 모든 스레드가 대기 상태에 들어갈 때까지 기다린 뒤
        start.countDown();  // 동시에 출발!
        done.await();       // 모두 끝날 때까지 대기

        // ---- 결과 확인 ----
        Course after = courseRepository.findById(course.getId()).orElseThrow();
        Integer actualRows = jdbcTemplate.queryForObject(
                "select count(*) from enrollments where course_id = ?", Integer.class, course.getId());

        System.out.println("==================== " + title + " ====================");
        System.out.println("강의              : " + after.getCode() + " " + after.getName() + " (정원 " + after.getCapacity() + ")");
        System.out.println("동시 신청 수      : " + threadCount);
        System.out.println("신청 성공 수      : " + success.get());
        System.out.println("신청 거절 수      : " + rejected.get() + "  (정원 초과/중복 등 규칙에 의한 거절)");
        System.out.println("기타 오류 수      : " + error.get());
        System.out.println("강의의 신청 인원  : " + after.getEnrolledCount() + "  (courses.enrolled_count)");
        System.out.println("실제 신청 내역 수 : " + actualRows + "  (enrollments 테이블 행 수)");
        rejectMessages.stream().distinct().limit(3)
                .forEach(m -> System.out.println("  거절 예시: " + m));
        errorMessages.stream().distinct().limit(5)
                .forEach(m -> System.out.println("  오류 예시: " + m));
        System.out.println("RESULT_LINE|" + success.get() + "|" + rejected.get() + "|" + error.get()
                + "|" + after.getEnrolledCount() + "|" + actualRows);
        System.out.println("================================================================");

        return new Result(success.get(), rejected.get(), error.get(), after.getEnrolledCount(), actualRows);
    }
}
