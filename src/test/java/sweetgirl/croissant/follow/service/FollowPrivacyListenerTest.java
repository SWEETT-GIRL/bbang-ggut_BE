package sweetgirl.croissant.follow.service;

import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.event.MemberPrivacyChangedEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowPrivacyListener")
class FollowPrivacyListenerTest {

	@Mock
	private FollowRequestCommandService followRequestCommandService;

	@InjectMocks
	private FollowPrivacyListener listener;

	@Test
	@DisplayName("공개로 돌리면 대기 중인 요청을 전부 수락한다")
	void toPublic() {
		listener.onPrivacyChanged(new MemberPrivacyChangedEvent(1L, false));

		then(followRequestCommandService).should().acceptAllOf(1L);
	}

	@Test
	@DisplayName("비공개로 돌릴 때는 아무것도 안 한다 — 기존 팔로워는 그대로")
	void toPrivate() {
		listener.onPrivacyChanged(new MemberPrivacyChangedEvent(1L, true));

		then(followRequestCommandService).shouldHaveNoInteractions();
	}
}
