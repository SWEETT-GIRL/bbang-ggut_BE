package sweetgirl.croissant.world.service;

/**
 * 지금 어디쯤 왔는가.
 *
 * @param planet     현재 행성 코드
 * @param stage      현재 고도 단계 코드. 단계를 안 둔 행성이면 비어 있다
 * @param nextPlanet 다음 행성 코드. 마지막 행성이면 비어 있다
 * @param toNext     다음 행성까지 남은 기록 수. 마지막 행성이면 0
 * @param ratio      이 행성 구간을 얼마나 지났는가(0.0 ~ 1.0)
 */
public record WorldProgress(
		String planet,
		String stage,
		String nextPlanet,
		int toNext,
		double ratio) {
}
