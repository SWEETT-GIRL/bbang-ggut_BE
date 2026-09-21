package sweetgirl.croissant.personalization.config;

import java.time.Duration;
import java.time.LocalDate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

/** 취향 판정의 손잡이 (설계 §1 · §2). #663 배치가 붙으면 값을 실측으로 다시 본다. */
@Getter
@Setter
@Component
@Validated
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
	/** 한 번에 옮길 수 있는 최대 일수 — 락을 쥔 채 몇 년치를 한 트랜잭션으로 도는 사고를 막는다. 백필은 달 단위로 나눠 돌린다 */
	private int ingestMaxRangeDays = 62;
	/**
	 * 담기 이벤트(CourseStoreAddedEvent)가 배포된 날. 담기 백필(코스 주인 기준)은 이 날짜 이하로만 허용한다 — 그 뒤의 담기는
	 * 이벤트가 담은 회원으로 남기므로 백필까지 넣으면 협업 코스에서 두 사람에게 귀속된다. **배포일과 맞춰 둔다**(PR #747 리뷰).
	 */
	private LocalDate courseAddEventSince = LocalDate.of(2026, 9, 19);

	/** 프로필 계산(#663 ②)의 손잡이. `personalization.profile.*` */
	@Valid
	private Profile profile = new Profile();

	@Getter
	@Setter
	public static class Profile {
		/** 신호를 읽는 창(일). 반감기 90일이면 365일 밖 신호는 0.06배라 잘라도 결과가 같다 */
		@Min(1)
		private int signalWindowDays = 365;
		/** 반감기(일): 유효 가중치 = 가중치 × 0.5^(경과일 ÷ 반감기) — 설계 §4.4 */
		@Min(1)
		private int halfLifeDays = 90;
		/** 검사 축의 가중치 — 설계 §4.2 "검사 ×3" */
		@Positive
		private double quizWeight = 3.0;
		/** 상세 조회 뒤 같은 세션에 저장·담기·계획(converted) — 보고 결정한 것 */
		@Positive
		private double viewConvertedWeight = 2.0;
		/** 조회 뒤 이 일수 안에 그 가게의 양의 강한 신호가 없으면 "안 담음". 그 전엔 아직 미정(0) */
		@PositiveOrZero
		private int viewConversionGraceDays = 7;
		/** 하루 보고 안 담음 */
		private double viewNotConvertedWeight = -0.3;
		/** 두 날 이상 보고 끝내 안 담음 — 가게당 한 번 */
		private double viewRepeatedNotConvertedWeight = -0.5;
		/** top_kinds 에 남기는 종류 수 */
		@PositiveOrZero
		private int topKindsLimit = 5;
		/**
		 * 추정 종류(#762): lift(좋아한 가게 중 그 종류가 대표인 가게 비율 ÷ 전체 빵집 비율) 하한. 케이크 · 쿠키처럼 어디나 대표인
		 * 종류가 모두에게 붙는 것(인기 쏠림)을 거른다 — 랩 09-20 실측값
		 */
		@Positive
		private double inferredKindMinLift = 1.5;
		/** 추정 종류 수 */
		@PositiveOrZero
		private int inferredKindsLimit = 3;
		/**
		 * 좋아한 가게가 많은 회원(강한 신호 가게 이 수 이상)은 그 종류가 {@code inferredKindMinStoresWhenMany} 곳 이상에 나와야 한다.
		 * 가게 한 곳에 딸려 온 희귀 종류가 lift 로 튀는 것(랩: 맘모스 · 소보로)을 막는다. 가게가 적은 회원은 한 곳이 근거의 전부라 1곳이면
		 * 된다. 배지의 태그 근거 기준(minStrongStoresForTagReason)과 같은 4
		 */
		@Min(1)
		private int inferredKindManyStores = 4;
		@Min(1)
		private int inferredKindMinStoresWhenMany = 2;
		/** 전체 빵집 비율의 모집단 — 추천 후보 풀과 같은 빵집다움 하한 */
		@PositiveOrZero
		private double kindBaseMinBakeryScore = 0.5;
		/** 전체 빵집 비율 캐시 — 가게 프로필은 dataops 적재 때만 바뀐다. 검사 제출 경로가 매번 집계 쿼리(30~80ms)를 돌지 않게 */
		private Duration kindBaseRateTtl = Duration.ofHours(1);
		/** 사용자 유형(§3.3)을 정하는 행동 창(일) */
		@Min(1)
		private int userTypeWindowDays = 90;
		@Min(1)
		private int plannerCourseAdds = 5;
		@Min(1)
		private int archiverRecords = 5;
		@Min(1)
		private int doerVisits = 3;
		@Min(1)
		private int collectorSaves = 10;
		@PositiveOrZero
		private double collectorSaveViewRatio = 0.5;
	}
}
