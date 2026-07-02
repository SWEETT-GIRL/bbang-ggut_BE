package sweetgirl.croissant.external.opensearch.code;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 검색 랭킹 튜닝 상수 — 가게명 정확(term) 부스트와 function_score 계수.
 *
 * <p>필드×서브필드 multi_match 부스트는 {@code SearchMatchProfile}(query 패키지), 이곳은 그 외 랭킹 값
 * (term 부스트, 인기도·거리 감쇠)을 모아 랭킹 전체를 한 곳에서 조정할 수 있게 한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SearchRankingConstants {

	// 가게명 정확 매칭 (rawQuery term 부스트) — 완전/정규화 일치를 부분 매칭 위로 끌어올린다.
	public static final float STORE_NAME_KEYWORD_BOOST = 20f;
	public static final float STORE_NAME_EDGE_BOOST = 80f;
	public static final float STORE_NAME_NGRAM_BOOST = 60f;

	// function_score — 텍스트 점수에 곱해지는 보정.
	public static final float BASELINE_WEIGHT = 1.0f;
	public static final float POPULARITY_FACTOR = 0.3f;
	public static final double POPULARITY_MISSING = 0.0;

	// 거리 감쇠(gauss) — 사용자 위치 기준.
	public static final String GAUSS_SCALE = "3km";
	public static final String GAUSS_OFFSET = "500m";
	public static final double GAUSS_DECAY = 0.5;
}
