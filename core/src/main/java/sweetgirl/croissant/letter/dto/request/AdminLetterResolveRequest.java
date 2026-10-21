package sweetgirl.croissant.letter.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대화를 닫거나 다시 여는 요청.
 *
 * <p>여는 쪽도 같은 자리에 두는 건 실수를 되돌릴 길이 필요해서다. 잘못 닫았을 때
 * 사용자가 편지를 한 통 더 보내야만 열리면, 어드민이 고칠 방법이 없다.
 */
@Getter
@NoArgsConstructor
public class AdminLetterResolveRequest {

	/** true면 해결로 닫고, false면 다시 연다. */
	private boolean resolved;
}
