package sweetgirl.croissant.breadlog.service;

import java.util.List;

/** 서버가 정한 재료만 사용한다. 공유 자산은 업로드·삭제 대상이 아닌 classpath 정적 파일이다. */
public final class TutorialRecordTemplate {
	public static final String CODE = "bbangdaengi-v1";
	public static final String PHOTO_KEY = "onboarding/bbangdaengi-v1.png";
	public static final String STORE_NAME = "빵댕이 빵집";
	public static final String BREAD_NAME = "소금빵";
	public static final List<String> TOWER_BREADS = List.of("croissant", "melonpan", "soboro", "bagel", "anpan");

	private TutorialRecordTemplate() {
		// 인스턴스 없이 서버의 고정 재료만 제공한다.
	}
}
