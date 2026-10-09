-- 부하 테스트 전에 신청 데이터를 초기화한다.
-- 실행: docker exec -i course-registration-mysql mysql -uapp -papp1234 course_registration < load-test/reset.sql
delete from enrollments;
update courses set enrolled_count = 0;
