package sweetgirl.croissant.world.service;

/**
 * 레벨. 행성 순서 × 고도 단계 순서를 1 부터 센 것이다(지구 마을 = 1, 지구 하늘 = 2 …).
 *
 * @param level        지금 레벨
 * @param levelStartAt 지금 레벨이 시작된 기록 수
 * @param levelUpAt    다음 레벨이 되는 기록 수. 마지막 레벨이면 비어 있다
 * @param planet       지금 행성 코드
 * @param stage        지금 고도 단계 코드. 단계를 안 둔 행성이면 비어 있다
 */
public record WorldLevel(int level, int levelStartAt, Integer levelUpAt, String planet, String stage) {
}
