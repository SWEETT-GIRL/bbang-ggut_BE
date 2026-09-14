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
