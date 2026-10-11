package com.example.courseregistration.service;

import com.example.courseregistration.domain.Course;
import com.example.courseregistration.domain.Enrollment;
import com.example.courseregistration.domain.Student;
import com.example.courseregistration.dto.CourseResponse;
import com.example.courseregistration.dto.EnrollmentResponse;
import com.example.courseregistration.repository.CourseRepository;
import com.example.courseregistration.repository.EnrollmentRepository;
import com.example.courseregistration.repository.StudentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 수강 신청의 핵심 규칙(비즈니스 로직)을 담당하는 서비스.
 *
 * 동시성 처리 (4단계-2):
 * - 정원 검사와 인원 증가를 "조건부 UPDATE" 한 문장으로 처리한다.
 *   (update courses set enrolled_count = enrolled_count + 1 where id = ? and enrolled_count < capacity)
 *   강의 행은 이 UPDATE 부터 커밋까지만 잠긴다.
 * - 같은 학생의 중복 신청은 enrollments 테이블의 (student_id, course_id) 유니크 제약이 최종적으로 막는다.
 *
 * DB 작업 줄이기 (4단계-4):
 * - 강의 목록은 CourseListCache 에서 읽는다 (1초 캐시). 목록 조회는 DB 를 거의 쓰지 않는다.
 * - 신청 전의 "강의 존재 / 정원 참" 검사도 캐시로 한다. 정원이 찬 강의에 대한 요청은
 *   트랜잭션도, 커넥션도, SQL 도 없이 즉시 거절된다. 최종 판단은 여전히 DB 의 조건부 UPDATE 가 한다.
 * - 학생 존재 검사 SELECT 는 없앴다. INSERT 의 외래 키 검사가 대신한다.
 *   그래서 신청 성공까지 보내는 SQL 은 "중복 검사 SELECT → UPDATE → INSERT" 세 문장이다.
 */
@Service
public class EnrollmentService {

    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CourseListCache courseListCache;
    private final TransactionTemplate transactionTemplate;

    public EnrollmentService(CourseRepository courseRepository,
                             StudentRepository studentRepository,
                             EnrollmentRepository enrollmentRepository,
                             CourseListCache courseListCache,
                             TransactionTemplate transactionTemplate) {
        this.courseRepository = courseRepository;
        this.studentRepository = studentRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.courseListCache = courseListCache;
        this.transactionTemplate = transactionTemplate;
    }

    /** 강의 목록 조회 (최대 1초 전 값) */
    public List<CourseResponse> getCourses() {
        return courseListCache.getAll();
    }

    /** 특정 학생의 신청 목록 조회 */
    @Transactional(readOnly = true)
    public List<Enrollment> getMyEnrollments(Long studentId) {
        findStudent(studentId); // 학생이 존재하는지 먼저 확인
        return enrollmentRepository.findAllByStudentIdWithCourse(studentId);
    }

    /**
     * 수강 신청.
     *
     * 이 메서드에는 @Transactional 을 붙이지 않았다. 붙이면 메서드에 들어오는 순간 DB 커넥션을 하나 잡는데,
     * 정원이 찬 강의에 대한 요청(부하 시 대부분)은 커넥션이 전혀 필요 없기 때문이다.
     * DB 가 필요한 구간만 transactionTemplate 으로 감싼다.
     */
    public EnrollmentResponse enroll(Long studentId, Long courseId) {
        // ---- DB 를 건드리지 않는 구간 (캐시만 본다) ----
        CourseResponse course = courseListCache.get(courseId)
                .orElseThrow(() -> new EnrollmentException("존재하지 않는 강의입니다. (강의 ID: " + courseId + ")"));

        // 정원 선검사: 캐시(최대 1초 전 값)가 "찼음"이면 바로 거절한다.
        // 그 사이 취소로 자리가 났을 수도 있지만, 취소 시 캐시를 비우므로 그 틈은 매우 짧다.
        // 반대로 캐시가 "안 찼음"인데 실제로는 찼다면 아래 UPDATE 가 거절한다. 정원은 항상 DB 기준으로 지켜진다.
        if (course.full()) {
            throw new EnrollmentException("정원이 가득 찼습니다: " + course.name()
                    + " (정원 " + course.capacity() + "명)");
        }

        // ---- 트랜잭션 구간 (여기서 커넥션을 잡는다) ----
        return transactionTemplate.execute(status -> {
            // 중복 신청 1차 검사 (친절한 메시지용). 동시에 들어온 경우는 아래 유니크 제약이 막는다.
            if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
                throw new EnrollmentException("이미 신청한 강의입니다: " + course.name());
            }

            // 정원 최종 검사 + 인원 증가를 UPDATE 한 문장으로. 여기서부터 커밋까지 강의 행이 잠긴다.
            int updated = courseRepository.increaseEnrolledCountIfNotFull(courseId);
            if (updated == 0) {
                // DB 가 "정원 참"이라고 했는데 캐시는 아직 "안 참"이었다 (최대 1초 낡은 값).
                // 캐시를 비워서 뒤따라오는 같은 강의 요청들이 UPDATE(락 줄)까지 오지 않고 위에서 바로 거절되게 한다.
                courseListCache.invalidate();
                throw new EnrollmentException("정원이 가득 찼습니다: " + course.name()
                        + " (정원 " + course.capacity() + "명)");
            }

            // getReferenceById: SELECT 없이 "ID 만 아는 껍데기 객체"를 만든다. INSERT 에는 ID 만 있으면 된다.
            // 학생이 존재하지 않으면 INSERT 의 외래 키 검사가 실패하므로 따로 조회하지 않는다.
            Student student = studentRepository.getReferenceById(studentId);
            Course courseRef = courseRepository.getReferenceById(courseId);
            Enrollment enrollment;
            try {
                enrollment = enrollmentRepository.save(new Enrollment(student, courseRef));
            } catch (DataIntegrityViolationException e) {
                // 예외를 던지면 트랜잭션이 롤백되므로 위에서 늘린 인원도 함께 되돌아간다.
                throw translate(e, studentId, course);
            }

            // 응답은 캐시의 강의 정보로 만든다 (껍데기 객체를 채우려고 SELECT 를 더 보내지 않기 위해)
            return new EnrollmentResponse(enrollment.getId(), studentId, course.id(), course.code(),
                    course.name(), course.professor(), enrollment.getCreatedAt());
        });
    }

    /** 수강 신청 취소 */
    public void cancel(Long studentId, Long enrollmentId) {
        transactionTemplate.executeWithoutResult(status -> {
            Enrollment enrollment = enrollmentRepository.findById(enrollmentId)
                    .orElseThrow(() -> new EnrollmentException("존재하지 않는 신청 내역입니다. (신청 ID: " + enrollmentId + ")"));

            // 다른 학생의 신청을 취소하지 못하도록 확인
            if (!enrollment.getStudent().getId().equals(studentId)) {
                throw new EnrollmentException("본인의 신청 내역만 취소할 수 있습니다.");
            }

            // 신청 내역을 먼저 지우고, 실제로 지워졌을 때만 인원을 줄인다.
            // (같은 신청을 동시에 두 번 취소해도 인원이 두 번 줄지 않는다)
            int deleted = enrollmentRepository.deleteByIdReturningCount(enrollmentId);
            if (deleted == 0) {
                throw new EnrollmentException("이미 취소된 신청 내역입니다. (신청 ID: " + enrollmentId + ")");
            }
            courseRepository.decreaseEnrolledCount(enrollment.getCourse().getId());
        });

        // 자리가 하나 났다. 캐시를 비워서 다음 요청부터 "정원 참" 이 아닌 새 값을 보게 한다.
        // (커밋이 끝난 뒤에 비워야 한다. 커밋 전에 비우면 다른 스레드가 옛 값을 다시 캐시할 수 있다)
        courseListCache.invalidate();
    }

    /**
     * INSERT 가 DB 제약에 걸렸을 때, 어떤 제약인지 보고 알맞은 메시지로 바꾼다.
     * - 유니크 제약(uk_enrollments_student_course): 같은 학생이 같은 강의를 동시에 두 번 신청
     * - 외래 키(student_id): 존재하지 않는 학생
     */
    private RuntimeException translate(DataIntegrityViolationException e, Long studentId, CourseResponse course) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uk_enrollments_student_course")) {
            return new EnrollmentException("이미 신청한 강의입니다: " + course.name());
        }
        if (message.contains("student_id")) {
            return new EnrollmentException("존재하지 않는 학생입니다. (학생 ID: " + studentId + ")");
        }
        return e; // 그 밖의 제약 위반은 그대로 둔다
    }

    private Student findStudent(Long studentId) {
        return studentRepository.findById(studentId)
                .orElseThrow(() -> new EnrollmentException("존재하지 않는 학생입니다. (학생 ID: " + studentId + ")"));
    }
}
