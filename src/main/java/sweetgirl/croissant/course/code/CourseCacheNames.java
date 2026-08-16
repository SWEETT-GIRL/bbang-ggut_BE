package sweetgirl.croissant.course.code;

/** 코스 도메인 캐시 이름. Redis 키는 "{이름}::{키}" 형태로 저장된다. */
public final class CourseCacheNames {

	/** GET /courses — 공개 코스 목록. 유저별 필드가 없어 전역으로 한 벌만 캐시한다. */
	public static final String PUBLIC_LIST = "course:public-list";

	private CourseCacheNames() {

	}
}
