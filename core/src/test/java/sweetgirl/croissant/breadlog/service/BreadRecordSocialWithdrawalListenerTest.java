package sweetgirl.croissant.breadlog.service;

import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.repository.BreadRecordCompanionRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordLikeRepository;
import sweetgirl.croissant.member.event.MemberWithdrawnEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("BreadRecordSocialWithdrawalListener")
class BreadRecordSocialWithdrawalListenerTest {

	@Mock
	private BreadRecordLikeRepository likeRepository;
	@Mock
	private BreadRecordCompanionRepository companionRepository;

	@InjectMocks
	private BreadRecordSocialWithdrawalListener listener;

	@Test
	@DisplayName("탈퇴 이벤트를 받으면 누른 좋아요와 같이 간 사람 태그를 전부 지운다")
	void deletesLikesAndCompanions() {
		listener.onWithdrawn(new MemberWithdrawnEvent(1L));

		then(likeRepository).should().deleteAllByMemberId(1L);
		then(companionRepository).should().deleteAllOf(1L);
	}
}
