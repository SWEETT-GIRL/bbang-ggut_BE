package sweetgirl.croissant.recommendation.config;

import java.util.HashMap;
import java.util.Map;

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
		private static final double DEFAULT_MEMBER_NOTABLE_TAG_ABOVE = 0.5;

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
		 * 회원 태그가 "도드라진다" 고 볼 문턱 — 태그마다 다르다. 회원 태그는 좋아한 가게 값의 평균이라 인기와 같이 가는 태그가 모두에게
		 * 높다(랩 09-20: 강한 신호 4곳 이상 회원의 94% 가 특별한메뉴 0.5 초과). 그래서 태그마다 max(0.5, 그 회원들의 상위 30% 경계)
		 * 로 둔다 — 0.5 는 "좋아한 가게들이 보통 빵집보다 높다"(참), 상위 30% 는 "다른 회원보다 두드러진다"(구별). 사전에 없는 키는
		 * 0.5. 랩 194명(운영 복제본) 기준 값이라 회원이 늘면 다시 잰다(#762). 배지와 이유 프롬프트가 같이 쓴다
		 */
		private Map<String, Double> memberNotableTagAbove = new HashMap<>(Map.of(
			"웨이팅", 0.555, "머물기", 0.5, "공간사진", 0.517, "가성비", 0.5,
			"건강한맛", 0.5, "특별한메뉴", 0.831, "대형", 0.5, "디저트", 0.534));
		/**
		 * 가게 태그가 도드라진다고 볼 문턱. 가게 값은 dataops 가 백분위로 편 값(0.5 = 보통 빵집, 표 없는 가게도 정확히 0.5)이라 하나로
		 * 된다. 0.5~0.6 은 표 몇 개 차이라 여유를 둔다 — 투표 태그마다 후보 빵집의 20~37% 가 넘는다(#759 리뷰 · #762)
		 */
		private double storeNotableTagAbove = 0.6;

		/** 그 태그의 회원 쪽 문턱 — 설정에 없는 키는 보통 빵집(0.5) */
		public double memberNotableTagAbove(String tagKey) {
			return memberNotableTagAbove.getOrDefault(tagKey, DEFAULT_MEMBER_NOTABLE_TAG_ABOVE);
		}

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
		/**
		 * 취향 점수를 무엇으로 낼지. AXIS 는 4축 코사인(지금까지의 방식), LABEL 은 라벨 아홉 값 코사인이다.
		 *
		 * <p>기본이 AXIS 인 이유: 라벨이 이기는지 아직 재지 않았다. 축을 지우지 않고 나란히 두어 랩에서 같은
		 * 코호트로 비교한 뒤에 바꾼다. LABEL 로 켜도 라벨이 없는 회원·가게는 그 항이 0 이라 인기 prior 로 돈다 —
		 * 폴백이 따로 필요 없다.
		 *
		 * <p>LABEL 의 중심화 상수는 여기 두지 않는다. 그건 가게 표의 평균이라 적재마다 바뀌어서
		 * {@link sweetgirl.croissant.recommendation.service.LabelCenterParams} 가 표에서 읽는다 — 아래 centerTaste
		 * 처럼 박아 두면 적재 뒤에 조용히 틀린다(2026-09-21 에 filling 중심이 0.035 → 0.517 로 부호까지 바뀌었다).
		 */
		private TasteSource tasteSource = TasteSource.AXIS;
		/** 전체 가게 축 평균(랩 09-14 실측: 달콤 쪽·고전 쪽으로 쏠려 있다). centerAxes 가 켜졌을 때만 쓴다 */
		private double centerTaste = -0.375;
		private double centerTexture = -0.167;
		private double centerFilling = -0.151;
		private double centerVibe = -0.549;
		private final Prior prior = new Prior();

		/** 취향 점수의 재료. */
		public enum TasteSource {
			/** 4축 코사인 — 맛이 달콤↔담백 하나라 짭짤·풍미가 접힌다. */
			AXIS,
			/** 라벨 아홉 값 코사인 — 짭짤·풍미·쫄깃이 따로 선다. */
			LABEL
		}
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
