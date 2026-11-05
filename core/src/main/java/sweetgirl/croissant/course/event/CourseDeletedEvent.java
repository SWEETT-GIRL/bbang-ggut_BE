package sweetgirl.croissant.course.event;

/** 코스가 지워졌다. 커밋 뒤 그 코스의 신호 구독을 전원 끊는다. */
public record CourseDeletedEvent(Long courseId) {
}
