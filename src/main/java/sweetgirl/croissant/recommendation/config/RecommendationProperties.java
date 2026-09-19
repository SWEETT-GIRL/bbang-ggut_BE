package sweetgirl.croissant.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * 추천 규칙의 손잡이 (설계 §5 · #660 · #661). 값은 전부 운영에서 바꿀 수 있어야 한다 — 상권 시드와 store_profile 적재 뒤
 * 실제 후보 분포를 보고 조정한다. 이유 문장·배지 같은 문구는 여기 없다: 문구는 #661 규칙 · #662 프롬프트가 정한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "recommendation")
public class RecommendationProperties {

	private final Area area = new Area();
	private final Anchor anchor = new Anchor();
	private final Score score = new Score();

	/** 상권 추천(진입점 1). */
	@Getter
	@Setter
	public static class Area {
		/** 빈 코스 카드에 서는 상권 칩 수. 설계 §1 의 "칩 3개" */
		private int maxChips = 3;
		/** 후보로 뽑는 가게 수. 설계 §5.3 의 15 — LLM 이 이 안에서 고른다(#662). 룰만 돌 땐 앞 N 곳이 코스가 된다 */
		private int candidateLimit = 15;
		/** 요청이 storeCount 를 안 보냈을 때 */
		private int defaultStoreCount = 4;
		private int minStoreCount = 1;
		/** 코스 한 일차에 담을 상한. 6곳이면 반나절 동선을 넘는다 */
		private int maxStoreCount = 6;
		/**
		 * 세션 id 헤더(X-Session-Id)가 없는 요청에서 "이 세션에서 본 가게"를 대신 자르는 창(분). 운영 request_log 실측(09-15):
		 * 한 회원의 연속 가게 조회 간격이 10분 안 91% · 30분 안 93% · 60분 안 93% — 30분이면 충분하고 60분은 이득이 없다.
		 */
		private int sessionWindowMinutes = 30;
		/**
		 * 세션 id 로 찾는 조회 로그의 시간 상한(시간). 값은 클라이언트 입력이라 고정값("0" 등)을 보내는 회귀가 생기면 역대 조회가
		 * 전부 "이번 세션"이 되어 SESSION 칩이 영구화된다(PR #741 리뷰) — 하루 넘은 로그는 세션 id 가 같아도 안 본다.
		 */
		private int sessionMaxAgeHours = 24;
	}

	/**
	 * 후보 점수(설계 v2 §5.3 · #661): score = cos·코사인(4축) + tag·태그 일치 + prior·인기 prior + kind·종류 겹침.
	 * 가중치는 손으로 정한 우선순위이지 데이터에서 나온 값이 아니다 — 피드백(LIKE·DISLIKE·RETRY·SAVE)이 쌓이면 여기서 조정한다.
	 */
	@Getter
	@Setter
	public static class Score {
		/** 코사인 항 가중치. 코사인은 −1~1 이라 범위가 다른 항의 두 배다 — 취향이 먼저라는 뜻이 그대로 담겨 있다 */
		private double cosineWeight = 1.0;
		/** 태그 일치(0~1) 가중치 — 사용자 태그는 #663 배치 전엔 비어 있어 0 이다 */
		private double tagWeight = 0.5;
		/** 인기 prior(0~1) 가중치 — 취향이 비슷할 때 순서를 가르는 정도 */
		private double priorWeight = 0.3;
		/** 종류 겹침(0~1) 가중치. 설계 §9 "0.3~0.4" 의 0.35 */
		private double kindWeight = 0.35;
		/** 빵집다움 하한. 프로필이 없거나 이 값 미만이면 후보에서 뺀다(분식·붕어빵·젤라또 혼입 방지, 설계 §9) */
		private double minBakeryScore = 0.5;
		/** 이 코사인을 넘어야 "취향 후보"다. 시그니처 문구·배지 판정에도 쓴다(설계 §9 "코사인 > 0.2") */
		private double tasteCosineThreshold = 0.2;
		/**
		 * 회원 태그를 문구(배지 · 이유 문장)에 쓰려면 강한 신호 가게가 이만큼 있어야 한다. 랩 실측(09-19)에서 축이 완성된 회원의 36.5% 가
		 * 강한 신호 가게 1곳이었고, 4곳 이상으로 잘라야 회원끼리 태그가 갈리는 것이 우연과 구분됐다. 점수의 태그 항에는 쓰지 않는다
		 */
		private int minStrongStoresForTagReason = 4;
		/**
		 * 태그가 "도드라진다" 고 볼 문턱. 회원 · 가게 값이 둘 다 이 값을 <b>넘어야</b> 태그 칩이 붙고, 이유 프롬프트도 이 값을 넘는
		 * 태그만 나열한다. v5 투표 태그는 0.5 가 보통 빵집이고 표가 없는 가게도 정확히 0.5 라 "초과" 다(09-19 사용자 결정: 태그
		 * 구분 없이 0.5 초과). 0.501 도 넘을 만큼 여유가 없어, v5 적재 뒤 태그별 가게 · 회원 비율을 재서 올릴 수 있게 설정으로 둔다
		 */
		private double notableTagAbove = 0.5;
		/** 풀 안 취향 후보가 이 수 미만이면 취향 항을 빼고 prior 순으로 채운다(fallback) */
		private int minTasteCandidates = 3;
		/**
		 * 축별 중심화(설계 §12 지렛대 1) — 가게·사용자 축에서 전체 가게 평균을 뺀 뒤 코사인을 잰다. 가게 축이 달콤·고전 쪽으로
		 * 쏠려 있어(랩 평균 맛 −0.38 · 분위기 −0.55) 원값 코사인은 담백 사용자를 굶긴다: 페르소나 실측(09-14, 59칸)에서 담백
		 * 계열의 취향 폴백이 10~13칸 → 켜면 1~3칸. 09-14 사용자 결정으로 기본 켜짐.
		 */
		private boolean centerAxes = true;
		/**
		 * 내가 기록한 가게(bread_record)를 후보에서 뺄지. 설계 §4.2 · 👎 이유 칩 "이미 가봤어요"의 전제(발견이 목적)라 기본은 뺀다.
		 * 재방문 수요가 확인되면 false 로 바꾼다 — 그러면 빼지 않고 recordedStorePenalty 만큼 점수를 깎는다(09-14 결정).
		 */
		private boolean excludeRecordedStores = true;
		/** excludeRecordedStores 가 false 일 때 기록한 가게의 점수에서 빼는 값 — 새 가게가 먼저 오되 취향이 강하게 맞는 단골은 남는다 */
		private double recordedStorePenalty = 0.3;
		/** 전체 가게 축 평균(랩 09-14 실측: 달콤 쪽·고전 쪽으로 쏠려 있다). centerAxes 가 켜졌을 때만 쓴다 */
		private double centerTaste = -0.375;
		private double centerTexture = -0.167;
		private double centerFilling = -0.151;
		private double centerVibe = -0.549;
		private final Prior prior = new Prior();
	}

	/**
	 * 인기 prior(0~1) = review·리뷰 수 + rating·별점 + popularity·인기 점수. 기준값은 랩(운영 덤프 08-20 + 프로필 v3) 분포:
	 * 리뷰 수 p95 2,300 · 별점 10명↑ 평균 4.6 · popularity_score 는 09-18 운영 실측(#663 ③ 새 산식, ACTIVE · >0 인 4,118곳) p90 4.3 —
	 * 주간 배치 로그의 p90 이 크게 벗어나면 다시 맞춘다.
	 */
	@Getter
	@Setter
	public static class Prior {
		private double reviewWeight = 0.4;
		private double ratingWeight = 0.4;
		private double popularityWeight = 0.2;
		/** ln(1+리뷰 수) / ln(1+cap) 을 1 로 자른다 */
		private int reviewCap = 2_300;
		/** 별점 수축의 가상 표본 수 m — 3명이 준 4.9 는 (3·4.9 + 10·4.6)/13 = 4.67 이 된다 */
		private int ratingShrink = 10;
		/** 표본이 없을 때 끌려가는 평균 별점 */
		private double ratingMean = 4.6;
		/** 이 구간을 0~1 로 편다 — 3.5 이하는 0, 5.0 은 1 */
		private double ratingFloor = 3.5;
		private double ratingCeil = 5.0;
		/** popularity_score / cap 을 1 로 자른다 */
		private double popularityCap = 4.3;
	}

	/** 앵커 추천(진입점 2). 링 규칙은 #661 09-13 확정 — 1km 에서 시작해 2km → 3km. */
	@Getter
	@Setter
	public static class Anchor {
		/** 링 너비. 안쪽 링부터 채운다 */
		private int ringM = 1_000;
		/** 조회 상한 반경. 5km 는 걸어서 한 시간이라 "근처"가 아니다 */
		private int maxRadiusM = 3_000;
		/** 행 하나에 저장하는 순위 길이 — 페이지는 이걸 자른다 */
		private int rankingSize = 30;
		/** 한 페이지 가게 수 (FE 레일 한 묶음) */
		private int pageSize = 9;
	}
}
