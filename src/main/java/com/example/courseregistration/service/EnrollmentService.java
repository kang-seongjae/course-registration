package com.example.courseregistration.service;

import com.example.courseregistration.domain.Course;
import com.example.courseregistration.domain.Enrollment;
import com.example.courseregistration.domain.Student;
import com.example.courseregistration.repository.CourseRepository;
import com.example.courseregistration.repository.EnrollmentRepository;
import com.example.courseregistration.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 수강 신청의 핵심 규칙(비즈니스 로직)을 담당하는 서비스.
 *
 * 1단계에서는 동시성 처리(락 등)를 일부러 넣지 않았다.
 * 여러 요청이 동시에 들어오면 정원 초과나 중복 신청이 발생할 수 있으며,
 * 이는 다음 단계에서 직접 확인하고 고칠 예정이다.
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
        Student student = findStudent(studentId);
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new EnrollmentException("존재하지 않는 강의입니다. (강의 ID: " + courseId + ")"));

        // 규칙 1: 같은 학생이 같은 강의를 두 번 신청할 수 없다
        if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
            throw new EnrollmentException("이미 신청한 강의입니다: " + course.getName());
        }

        // 규칙 2: 정원이 차면 신청을 거절한다
        if (course.isFull()) {
            throw new EnrollmentException("정원이 가득 찼습니다: " + course.getName()
                    + " (" + course.getEnrolledCount() + "/" + course.getCapacity() + ")");
        }

        course.increaseEnrolledCount(); // 트랜잭션이 끝날 때 JPA 가 자동으로 UPDATE 를 실행한다
        return enrollmentRepository.save(new Enrollment(student, course));
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

        enrollment.getCourse().decreaseEnrolledCount();
        enrollmentRepository.delete(enrollment);
    }

    private Student findStudent(Long studentId) {
        return studentRepository.findById(studentId)
                .orElseThrow(() -> new EnrollmentException("존재하지 않는 학생입니다. (학생 ID: " + studentId + ")"));
    }
}
