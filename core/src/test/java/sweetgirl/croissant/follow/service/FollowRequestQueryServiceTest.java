package sweetgirl.croissant.follow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.follow.dto.response.FollowRequestResponse;
import sweetgirl.croissant.follow.entity.MemberFollowRequest;
import sweetgirl.croissant.follow.repository.MemberFollowRequestRepository;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowRequestQueryService")
class FollowRequestQueryServiceTest {

	private static final Long ME = 1L;

	@Mock
	private MemberFollowRequestRepository memberFollowRequestRepository;
	@Mock
	private MemberReader memberReader;
	@Mock
	private CharacterFaceReader characterFaceReader;

	@InjectMocks
	private FollowRequestQueryService service;

	private Member member(Long id) {
		Member member = Member.builder().nickname("회원" + id).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	@Test
	@DisplayName("받은 요청을 최신순으로, 줄마다 요청한 사람과 얼굴을 채운다")
	void received() {
		MemberFollowRequest request = MemberFollowRequest.of(member(2L), member(ME));
		given(memberFollowRequestRepository.findReceived(eq(ME), any()))
			.willReturn(new SliceImpl<>(List.of(request), PageRequest.of(0, 20), true));
		given(characterFaceReader.facesOf(List.of(2L)))
			.willReturn(Map.of(2L, new CharacterFace("dog", "world/dog/front.png")));

		SliceResponse<FollowRequestResponse> result = service.getReceived(ME, 0, 20);

		assertThat(result.hasNext()).isTrue();
		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).memberId()).isEqualTo(2L);
		assertThat(result.content().get(0).nickname()).isEqualTo("회원2");
		assertThat(result.content().get(0).characterCode()).isEqualTo("dog");
	}

	@Test
	@DisplayName("페이지 크기는 50 을 넘지 못하고 음수 페이지는 0 이다")
	void clampsPage() {
		given(memberFollowRequestRepository.findReceived(eq(ME), any()))
			.willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 50), false));
		given(characterFaceReader.facesOf(List.of())).willReturn(Map.of());

		service.getReceived(ME, -3, 500);

		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		then(memberFollowRequestRepository).should().findReceived(eq(ME), captor.capture());
		assertThat(captor.getValue().getPageNumber()).isZero();
		assertThat(captor.getValue().getPageSize()).isEqualTo(FollowRequestQueryService.MAX_PAGE_SIZE);
	}

	@Test
	@DisplayName("받은 요청 수")
	void count() {
		given(memberFollowRequestRepository.countByIdTargetId(ME)).willReturn(4L);

		assertThat(service.countReceived(ME)).isEqualTo(4L);
	}

	@Test
	@DisplayName("게스트는 MEMBER403_GUEST")
	void guest() {
		willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED)).given(memberReader).requireLoggedIn(ME);

		assertThatThrownBy(() -> service.getReceived(ME, 0, 20)).isInstanceOf(MemberException.class);
		assertThatThrownBy(() -> service.countReceived(ME)).isInstanceOf(MemberException.class);
		then(memberFollowRequestRepository).shouldHaveNoInteractions();
	}
}
