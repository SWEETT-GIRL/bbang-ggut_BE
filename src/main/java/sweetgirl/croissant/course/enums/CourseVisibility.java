package sweetgirl.croissant.course.enums;

/**
 * 코스 공개 범위.
 *
 * PUBLIC   목록 ✓ · 링크 ✓ · 찜 ✓
 * UNLISTED 목록 ✗ · 링크 ✓ · 찜 ✗ — 공유용이지 보관용이 아니다
 * PRIVATE  목록 ✗ · 링크 ✗ · 찜 ✗ — 나와 협업 멤버만
 *
 * 링크 공개(UNLISTED)가 열리기 전까지는 is_public 과 같은 값을 유지하는 그림자 컬럼이다.
 * 판정은 아직 is_public 으로 한다.
 */
public enum CourseVisibility {
	PUBLIC,
	UNLISTED,
	PRIVATE;

	public static CourseVisibility fromPublic(boolean isPublic) {
		return isPublic ? PUBLIC : PRIVATE;
	}
}
