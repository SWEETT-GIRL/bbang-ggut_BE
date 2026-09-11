/**
 * 개인화 — 사람마다 다른 취향 데이터를 쌓고 계산한다.
 *
 * <p>경계: 사람이 "어떤지"는 여기, 가게가 "어떤지"는 store, 그걸로 "무엇을 보여줄지" 정하고 남기는 일은 recommendation.
 * <ul>
 *   <li>여기: 사용자 취향 프로필(빵BTI 타입과 4축), 행동 신호, 4축 값 타입 {@code TasteAxis}, 빵BTI 검사, 취향 재계산 배치(#663)</li>
 *   <li>store: 가게 프로필 {@code StoreProfile}. 누가 보든 같은 가게 사실이고 dataops 가 쓴다</li>
 *   <li>recommendation: 상권, 추천 결과와 그 반응</li>
 * </ul>
 *
 * <p>{@code TasteAxis} 는 가게와 사용자가 같은 종류의 값(메뉴 극값의 가중 평균, −1~+1 소수)을 담아서 둘 다 임베드한다.
 * 메뉴의 축은 −1·+1 라벨이라 따로 둔다.
 *
 * <p>다른 도메인이 신호를 남길 때(저장 해제 등)는 이벤트로 넘긴다 — 가게 검색의 {@code SearchMissEvent} 처럼.
 * 서비스끼리 서로 생성자 주입하면 순환 참조로 부팅이 실패한다.
 */
package sweetgirl.croissant.personalization;
