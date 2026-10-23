package sweetgirl.croissant.breadlog.service;

import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.repository.BreadRecordCompanionRepository;
import sweetgirl.croissant.member.event.MemberBlockedEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanionBlockListener")
class CompanionBlockListenerTest {

	@Mock
	private BreadRecordCompanionRepository companionRepository;

	@InjectMocks
	private CompanionBlockListener listener;

	@Test
	@DisplayName("차단 이벤트를 받으면 두 사람 사이 같이 간 사람 태그를 양방향으로 지운다")
	void deletesBothDirections() {
		listener.onBlocked(new MemberBlockedEvent(1L, 2L));

		then(companionRepository).should().deleteBetween(1L, 2L);
	}
}
