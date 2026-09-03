package sweetgirl.croissant.course.enums;

/** 초대 미리보기 상태. 랜딩이 카드를 그려야 하므로 예외 대신 이 값으로 200 을 준다. */
public enum CourseInviteState {
	OK,
	EXPIRED,
	INVALID,
	FULL,
	DELETED
}
