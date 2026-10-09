package com.example.courseregistration.config;

import com.example.courseregistration.domain.Course;
import com.example.courseregistration.domain.Student;
import com.example.courseregistration.repository.CourseRepository;
import com.example.courseregistration.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 애플리케이션이 시작될 때 샘플 데이터(강의 10개, 학생 100명)를 넣는다.
 * 이미 데이터가 있으면 아무것도 하지 않는다. (재시작해도 중복으로 쌓이지 않도록)
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;

    public DataInitializer(CourseRepository courseRepository, StudentRepository studentRepository) {
        this.courseRepository = courseRepository;
        this.studentRepository = studentRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (courseRepository.count() == 0) {
            List<Course> courses = List.of(
                    new Course("CS101", "컴퓨터과학 개론", "김철수", 30),
                    new Course("CS201", "자료구조", "이영희", 25),
                    new Course("CS301", "알고리즘", "박민수", 20),
                    new Course("CS302", "운영체제", "최지우", 15),
                    new Course("CS303", "데이터베이스", "정하늘", 10),
                    new Course("CS401", "컴퓨터 네트워크", "강동원", 5),
                    new Course("CS402", "소프트웨어 공학", "윤서연", 3),
                    new Course("MA101", "미적분학", "한지민", 40),
                    new Course("EN101", "영어 회화", "오세훈", 2),
                    new Course("PH101", "일반 물리학", "송혜교", 1)
            );
            courseRepository.saveAll(courses);
            log.info("샘플 강의 {}개를 등록했습니다.", courses.size());
        }

        if (studentRepository.count() == 0) {
            List<Student> students = new ArrayList<>();
            for (int i = 1; i <= 100; i++) {
                students.add(new Student(String.format("학생%03d", i)));
            }
            studentRepository.saveAll(students);
            log.info("샘플 학생 {}명을 등록했습니다.", students.size());
        }
    }
}
