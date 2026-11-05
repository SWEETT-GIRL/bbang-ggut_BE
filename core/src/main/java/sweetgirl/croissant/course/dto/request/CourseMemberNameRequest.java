package sweetgirl.croissant.course.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 초대 수락 요청 본문. {@code displayName} 은 설치된 앱이 아직 보내므로 받기만 하고 쓰지 않는다 —
 * 코스 안 이름 짓기는 2026-10-21 에 없앴고 사람 이름은 회원 닉네임이다.
 */
@Getter
@NoArgsConstructor
public class CourseMemberNameRequest {

	private String displayName;
}
