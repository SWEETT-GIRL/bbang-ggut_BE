package sweetgirl.croissant.personalization.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/** 취향 판정의 손잡이 (설계 §1 · §2). #663 배치가 붙으면 값을 실측으로 다시 본다. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "personalization")
public class PersonalizationProperties {

	/** 검사 없이 추천 카드를 칩 상태로 띄우려면 강한 신호(저장 · 담기 · 기록) 가게가 이만큼 있어야 한다. 설계 §1 의 "3곳 미만이면 검사" */
	private int strongSignalStoresForChips = 3;

	/** 4문항(축마다 하나) 검사 결과의 확신도 */
	private double shortQuizConfidence = 0.7;

	/** 8문항(축마다 둘) 검사 결과의 확신도 */
	private double fullQuizConfidence = 0.9;
	/**
	 * 신호 적재(#663): 가게 상세를 본 뒤 이 시간 안에 같은 가게를 저장 · 담기 · 계획했으면 "보고 결정한 것"(converted). 추천의
	 * 세션 창(30분, 운영 실측 연속 조회 93%)과 같은 값이다.
	 */
	private int ingestConversionWindowMinutes = 30;
}
