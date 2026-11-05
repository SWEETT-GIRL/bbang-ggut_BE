package sweetgirl.croissant.course.event;

/** 회원이 코스에서 나갔다(나가기·탈퇴·방장 승계 뒤 나가기). 커밋 뒤 그 회원의 코스 신호 구독을 끊는다 — DRAFT 에 내용이 실리기 때문. */
public record CourseMemberLeftEvent(Long courseId, Long memberId) {
}
