package sweetgirl.croissant.personalization.event;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.event.GuestMergedEvent;
import sweetgirl.croissant.personalization.service.UserTasteProfileCommandService;

@ExtendWith(MockitoExtension.class)
class GuestMergedEventListenerTest {

	@Mock
	private UserTasteProfileCommandService userTasteProfileCommandService;
	@InjectMocks
	private GuestMergedEventListener listener;

	@Test
	@DisplayName("병합이 커밋되면 회원 프로필을 다시 계산한다")
	void onGuestMerged_recomputesTarget() {
		listener.onGuestMerged(new GuestMergedEvent(10L, 20L));

		then(userTasteProfileCommandService).should().recompute(20L);
	}

	@Test
	@DisplayName("재계산이 실패해도 던지지 않는다 — 로그만 남기고 04:00 배치가 다시 계산한다")
	void onGuestMerged_swallowsFailure() {
		given(userTasteProfileCommandService.recompute(20L)).willThrow(new IllegalStateException("tags 파싱 실패"));

		assertThatCode(() -> listener.onGuestMerged(new GuestMergedEvent(10L, 20L))).doesNotThrowAnyException();
	}
}
