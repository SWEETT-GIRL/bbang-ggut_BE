package sweetgirl.croissant.personalization.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 취향 태그 12개 (설계 §2). store_profile.tags · user_taste_profile.tags 의 jsonb 키는 이 한글 이름 그대로다 — LLM 프롬프트
 * 사전(§7.2)과 같은 문자열이라 변환표가 없다. 프로필 계산은 이 12개만 읽고 쓴다(모르는 키는 버린다).
 */
@Getter
@RequiredArgsConstructor
public enum TasteTag {
	CAFE("카페형"),
	TAKEOUT("테이크아웃"),
	OLD_SCHOOL("노포"),
	NEW_OPEN("신상"),
	LARGE("대형"),
	NEIGHBORHOOD("동네빵집"),
	DATE("데이트"),
	SOLO("혼빵"),
	GIFT("선물"),
	WORTH_WAITING("웨이팅감수"),
	MORNING("아침"),
	EVENING("저녁");

	private final String key;
}
