package sweetgirl.croissant.course.dto.request;

import java.util.UUID;

/**
 * 한 번 탭으로 다녀왔다고 표시하기. 본문 없이 보내도 된다.
 *
 * @param requestId 이 요청의 번호(선택). 응답을 못 받아 다시 보낼 때 같은 값을 쓰면 기록이 둘 생기지 않는다
 */
public record CourseQuickVisitRequest(UUID requestId) {
}
