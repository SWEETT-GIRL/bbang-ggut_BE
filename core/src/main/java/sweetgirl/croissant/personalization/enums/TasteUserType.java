package sweetgirl.croissant.personalization.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 90일 행동 비율로 정하는 사용자 유형 (설계 §3.3). 추천의 앞세울 진입점과 제외 규칙이 달라진다. */
@Getter
@RequiredArgsConstructor
public enum TasteUserType {

	PLANNER("플래너. 코스 담기를 앞세운다"),
	ARCHIVER("아카이버. 기록한 가게 제외를 강화한다"),
	BROWSER("브라우저. 가게 담기(앵커 추천)를 앞세운다"),
	COLLECTOR("수집형. 저장 하나의 무게를 낮춘다"),
	DOER("실행형. 영업 중인 가게만");

	private final String description;
}
