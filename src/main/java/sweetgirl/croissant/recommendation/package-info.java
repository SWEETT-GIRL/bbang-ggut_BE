/**
 * 추천 — 사람(personalization)과 가게(store)의 데이터로 무엇을 보여줄지 정하고, 보여준 것과 그 반응을 남긴다.
 *
 * <p>상권(course_area), 추천 결과(recommendation), 추천 반응(recommendation_feedback)이 여기 있다.
 * 취향 재계산 배치(#663)는 추천 반응도 신호로 읽지만, 저장·담기·기록 표를 읽을 때처럼 배치 SQL 로 읽어서
 * personalization 이 이 패키지를 import 하지 않게 한다.
 */
package sweetgirl.croissant.recommendation;
