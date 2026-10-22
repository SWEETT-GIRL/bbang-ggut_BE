package sweetgirl.croissant.comment.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.comment.code.CommentErrorCode;
import sweetgirl.croissant.comment.exception.CommentException;
import sweetgirl.croissant.moderation.service.ForbiddenWordFilter;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommentContentPolicy")
class CommentContentPolicyTest {

	@Mock
	private ForbiddenWordFilter forbiddenWordFilter;

	@InjectMocks
	private CommentContentPolicy policy;

	private void assertRejected(String content, CommentErrorCode expected) {
		assertThatThrownBy(() -> policy.validateComment(content))
			.isInstanceOf(CommentException.class)
			.extracting("errorCode").isEqualTo(expected);
	}

	@Test
	@DisplayName("200자 · 줄바꿈 3줄까지, 빈 글은 안 된다")
	void length() {
		given(forbiddenWordFilter.containsForbidden(anyString())).willReturn(false);
		assertThatCode(() -> policy.validateComment("가".repeat(200))).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateComment("한\n두\n세\n네")).doesNotThrowAnyException();

		assertRejected("가".repeat(201), CommentErrorCode.COMMENT_TOO_LONG);
		assertRejected("한\n두\n세\n네\n다섯", CommentErrorCode.COMMENT_TOO_LONG);
		assertRejected("   ", CommentErrorCode.COMMENT_TOO_LONG);
		assertRejected(null, CommentErrorCode.COMMENT_TOO_LONG);
	}

	@Test
	@DisplayName("금칙어 → FORBIDDEN_WORD, 링크 → LINK, 전화번호 → PHONE, 사유 코드가 다 다르다")
	void reasons() {
		given(forbiddenWordFilter.containsForbidden("나쁜말")).willReturn(true);
		assertRejected("나쁜말", CommentErrorCode.COMMENT_FORBIDDEN_WORD);

		given(forbiddenWordFilter.containsForbidden(anyString())).willReturn(false);
		assertRejected("여기 봐요 https://example.com/bread", CommentErrorCode.COMMENT_LINK);
		assertRejected("www.bread.kr 들어와", CommentErrorCode.COMMENT_LINK);
		assertRejected("bread-shop.com/sale", CommentErrorCode.COMMENT_LINK);
		assertRejected("연락 주세요 010-1234-5678", CommentErrorCode.COMMENT_PHONE);
		assertRejected("+82 10 1234 5678", CommentErrorCode.COMMENT_PHONE);
		assertThatCode(() -> policy.validateComment("소금빵 3개 사서 1234 번 버스 타고 집에")).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateComment("빵집 이름이 com 이에요")).doesNotThrowAnyException();
	}
}
