package sweetgirl.croissant.course.enums;

/**
 * 코스 공개 범위.
 *
 * PUBLIC   목록 ✓ · 링크 ✓ · 찜 ✓
 * UNLISTED 목록 ✗ · 링크 ✓ · 찜 ✗ — 공유용이지 보관용이 아니다
 * PRIVATE  목록 ✗ · 링크 ✗ · 찜 ✗ — 나와 협업 멤버만
 *
 * is_public 은 구버전 앱이 읽어 한 릴리즈 더 남긴다 — Course.updateVisibility 가 PUBLIC 일 때만 true 로 같이 쓴다.
 * 그래서 "공개일 때만" 판정(공개 목록·찜·따라가기)은 isPublic() 으로, UNLISTED 구분이 필요한 판정(상세·포크)은 이 값과 share_token 으로 한다.
 */
public enum CourseVisibility {
	PUBLIC,
	UNLISTED,
	PRIVATE;

	public static CourseVisibility fromPublic(boolean isPublic) {
		return isPublic ? PUBLIC : PRIVATE;
	}
}
