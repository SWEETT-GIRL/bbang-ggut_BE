package sweetgirl.croissant.dessert.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * dessert-radar 가 뉴스·인스타 신호로 매긴 판정.
 *
 * <p>칩 줄은 이 순서로 선다. 알파벳순(COOLING → HOT → …)으로 두면 식는 중인 게 앞에 온다.
 */
@Getter
@RequiredArgsConstructor
public enum DessertTrendStatus {

	/** 대란 — 인스타 트렌딩에 뉴스도 여러 번 */
	HOT(0),

	/** 뜨는 중 — 인스타에선 뜨는데 뉴스엔 아직 적다 */
	RISING(1),

	/** 신호가 엇갈린다 */
	UNCERTAIN(2),

	/** 식는 중 — 게시물은 많은데 최근 비율이 낮다 */
	COOLING(3);

	private final int chipOrder;
}
