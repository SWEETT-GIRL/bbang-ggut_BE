package sweetgirl.croissant.course.event;

import java.time.LocalDateTime;

/**
 * 코스에 가게를 담음. memberId 는 담는 요청을 보낸 회원이다 — course_stores 에는 담은 사람이 없어 코스 주인으로 세면 협업 코스에서
 * 일행이 담은 가게가 내 신호가 된다(#732 S4). 개인화가 COURSE_ADD 신호(+1.5)로 받는다(#663). recommendationId 가 있으면 추천에서 담은 것.
 */
public record CourseStoreAddedEvent(Long memberId, Long courseId, Long storeId, Long recommendationId,
		LocalDateTime addedAt) {
}
