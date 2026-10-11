package com.example.courseregistration.service;

import com.example.courseregistration.domain.Course;
import com.example.courseregistration.domain.Enrollment;
import com.example.courseregistration.domain.Student;
import com.example.courseregistration.repository.CourseRepository;
import com.example.courseregistration.repository.EnrollmentRepository;
import com.example.courseregistration.repository.StudentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 수강 신청의 핵심 규칙(비즈니스 로직)을 담당하는 서비스.
 *
 * 동시성 처리 (4단계-2):
 * - 정원 검사와 인원 증가를 "조건부 UPDATE" 한 문장으로 처리한다.
 *   (update courses set enrolled_count = enrolled_count + 1 where id = ? and enrolled_count < capacity)
 *   강의 행은 이 UPDATE 부터 커밋까지만 잠기므로, 락을 쥐는 시간이 2단계의 FOR UPDATE 방식보다 짧다.
 * - 같은 학생의 중복 신청은 enrollments 테이블의 (student_id, course_id) 유니크 제약이 최종적으로 막는다.
 *   코드의 existsBy... 검사는 "친절한 오류 메시지를 빨리 주기 위한" 1차 검사일 뿐이다.
 */
@Service
public class EnrollmentService {

    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;

    public EnrollmentService(CourseRepository courseRepository,
                             StudentRepository studentRepository,
                             EnrollmentRepository enrollmentRepository) {
        this.courseRepository = courseRepository;
        this.studentRepository = studentRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    /** 강의 목록 조회 */
    @Transactional(readOnly = true)
    public List<Course> getCourses() {
        return courseRepository.findAll();
    }

    /** 특정 학생의 신청 목록 조회 */
    @Transactional(readOnly = true)
    public List<Enrollment> getMyEnrollments(Long studentId) {
        findStudent(studentId); // 학생이 존재하는지 먼저 확인
        return enrollmentRepository.findAllByStudentIdWithCourse(studentId);
    }

    /** 수강 신청 */
    @Transactional
    public Enrollment enroll(Long studentId, Long courseId) {
        // ---- 여기까지는 락 없이 실행된다 (일반 SELECT) ----
        Student student = findStudent(studentId);
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new EnrollmentException("존재하지 않는 강의입니다. (강의 ID: " + courseId + ")"));

        // 규칙 1 (1차 검사): 이미 신청한 강의면 바로 거절한다.
        // 이 검사와 아래 INSERT 사이에 같은 학생의 다른 요청이 끼어들 수 있지만, 그 경우는 유니크 제약이 막는다.
        if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
            throw new EnrollmentException("이미 신청한 강의입니다: " + course.getName());
        }

        // 규칙 2 (1차 검사): 락 없이 읽은 값으로도 정원이 찼으면 바로 거절한다.
        // 이 값은 조금 낡았을 수 있지만, 정원이 찬 강의는 다시 비기 전까지 계속 차 있으므로
        // 락을 잡고 확인할 필요가 없다. (정원이 찬 뒤 몰려오는 요청이 락 줄에 서지 않게 한다)
        if (course.isFull()) {
            throw new EnrollmentException("정원이 가득 찼습니다: " + course.getName()
                    + " (정원 " + course.getCapacity() + "명)");
        }

        // ---- 여기서부터 커밋까지가 강의 행을 잠그는 구간 ----
        // 규칙 2 (최종 검사): 정원 검사 + 인원 증가를 UPDATE 한 문장으로. 바뀐 행이 0 이면 그 사이에 정원이 찬 것이다.
        int updated = courseRepository.increaseEnrolledCountIfNotFull(courseId);
        if (updated == 0) {
            throw new EnrollmentException("정원이 가득 찼습니다: " + course.getName()
                    + " (정원 " + course.getCapacity() + "명)");
        }

        try {
            return enrollmentRepository.save(new Enrollment(student, course));
        } catch (DataIntegrityViolationException e) {
            // 규칙 1 (최종 검사): 유니크 제약 위반 = 같은 학생이 같은 강의를 동시에 두 번 신청한 경우.
            // 예외를 던지면 트랜잭션이 롤백되므로 위에서 늘린 인원도 함께 되돌아간다.
            throw new EnrollmentException("이미 신청한 강의입니다: " + course.getName());
        }
    }

    /** 수강 신청 취소 */
    @Transactional
    public void cancel(Long studentId, Long enrollmentId) {
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
    }

    private Student findStudent(Long studentId) {
        return studentRepository.findById(studentId)
                .orElseThrow(() -> new EnrollmentException("존재하지 않는 학생입니다. (학생 ID: " + studentId + ")"));
    }
}
