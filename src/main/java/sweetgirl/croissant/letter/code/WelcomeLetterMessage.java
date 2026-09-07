package sweetgirl.croissant.letter.code;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 새 회원에게 편찬위원회가 먼저 거는 인사.
 *
 * <p>문구를 상수로 둔 건 어드민 화면에서 고칠 수 있게 만들기 전까지의 자리다 —
 * 바꾸려면 여기만 고치고 배포하면 된다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WelcomeLetterMessage {

	public static final String BODY = """
			반가워요! 🎉

			빵지도 편찬위원회입니다.
			쓰시다가 불편했던 점, 있으면 좋을 기능,
			나누고 싶은 빵집 이야기까지 여기로 보내주세요.""";
}
