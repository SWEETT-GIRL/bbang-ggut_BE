package sweetgirl.croissant.breadlog.service;

import java.util.List;

/** 앱에서 렌더링할 수 있는 빵탑 종류. 사진과 예시 기록은 발행된 서버 템플릿을 사용한다. */
public final class TutorialRecordTemplate {
	public static final String CODE = "bbangdaengi-v1";
	public static final List<String> TOWER_BREADS = List.of("croissant", "melonpan", "soboro", "bagel", "anpan");

	private TutorialRecordTemplate() {
		// 인스턴스 없이 서버의 고정 재료만 제공한다.
	}
}
