package sweetgirl.croissant.follow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.follow.code.FollowErrorCode;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.follow.entity.MemberFollow;
import sweetgirl.croissant.follow.exception.FollowException;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowQueryService")
class FollowQueryServiceTest {

	private static final Long VIEWER = 1L;
	private static final Long TARGET = 2L;

	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private SocialMemberReader socialMemberReader;
	@Mock
	private CharacterFaceReader characterFaceReader;

	@InjectMocks
	private FollowQueryService followQueryService;

	private Member member(Long id, String nickname) {
		Member member = Member.builder().nickname(nickname).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private void givenTargetVisible() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET, "대상")));
		given(memberBlockRepository.existsBetween(VIEWER, TARGET)).willReturn(false);
	}

	@Test
	@DisplayName("팔로워 줄마다 내가 팔로우하는지 · 나를 팔로우하는지를 id 묶음 두 번으로 채운다")
	void followers_marksRelations() {
		givenTargetVisible();
		Member first = member(10L, "에이");
		Member second = member(11L, "비");
		given(memberFollowRepository.findFollowers(eq(TARGET), eq(VIEWER), eq(""), any()))
			.willReturn(new SliceImpl<>(List.of(MemberFollow.of(first, member(TARGET, "대상")),
				MemberFollow.of(second, member(TARGET, "대상"))), Pageable.ofSize(20), true));
		given(memberFollowRepository.findFolloweeIdsIn(VIEWER, List.of(10L, 11L))).willReturn(List.of(10L));
		given(memberFollowRepository.findFollowerIdsIn(VIEWER, List.of(10L, 11L))).willReturn(List.of(11L));

		SliceResponse<FollowMemberResponse> result = followQueryService.getFollowers(VIEWER, TARGET, null, 0, 20);

		assertThat(result.hasNext()).isTrue();
		assertThat(result.content()).containsExactly(
			new FollowMemberResponse(10L, "에이", true, false, null, null),
			new FollowMemberResponse(11L, "비", false, true, null, null));
	}

	@Test
	@DisplayName("비로그인(viewer null)이면 관계 조회를 하지 않고 전부 false 다")
	void followings_withoutViewer() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET, "대상")));
		Member first = member(10L, "에이");
		given(memberFollowRepository.findFollowings(eq(TARGET), eq(null), eq(""), any()))
			.willReturn(
				new SliceImpl<>(List.of(MemberFollow.of(member(TARGET, "대상"), first)), Pageable.ofSize(20), false));

		SliceResponse<FollowMemberResponse> result = followQueryService.getFollowings(null, TARGET, null, 0, 20);

		assertThat(result.content()).containsExactly(new FollowMemberResponse(10L, "에이", false, false, null, null));
		then(memberFollowRepository).should(never()).findFolloweeIdsIn(any(), any());
		then(memberBlockRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("q 는 다듬어 앞글자로 넘기고(공백만이면 null), 줄마다 얼굴을 붙인다")
	void followers_filtersByPrefixAndAddsFace() {
		givenTargetVisible();
		Member first = member(10L, "에이");
		given(memberFollowRepository.findFollowers(eq(TARGET), eq(VIEWER), eq("에"), any()))
			.willReturn(new SliceImpl<>(List.of(MemberFollow.of(first, member(TARGET, "대상"))), Pageable.ofSize(20),
				false));
		given(characterFaceReader.facesOf(List.of(10L)))
			.willReturn(Map.of(10L, new CharacterFace("CROISSANT", "world/croissant.png")));

		SliceResponse<FollowMemberResponse> result = followQueryService.getFollowers(VIEWER, TARGET, " 에 ", 0, 20);

		assertThat(result.content()).containsExactly(
			new FollowMemberResponse(10L, "에이", false, false, "CROISSANT", "world/croissant.png"));
		assertThat(FollowQueryService.prefixOf("   ")).isEmpty();
	}

	@Test
	@DisplayName("page 음수 · size 초과는 0 · 50 으로 눌러 넘긴다")
	void clampsPageable() {
		givenTargetVisible();
		given(memberFollowRepository.findFollowers(eq(TARGET), eq(VIEWER), eq(""), any()))
			.willReturn(new SliceImpl<>(List.of(), Pageable.ofSize(50), false));

		followQueryService.getFollowers(VIEWER, TARGET, null, -3, 500);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		then(memberFollowRepository).should().findFollowers(eq(TARGET), eq(VIEWER), eq(""), pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isZero();
		assertThat(pageable.getValue().getPageSize()).isEqualTo(FollowQueryService.MAX_PAGE_SIZE);
	}

	@Test
	@DisplayName("상대가 없거나 게스트 · 봇 · 탈퇴면 FOLLOW404_TARGET")
	void targetNotFound() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.empty());

		assertThatThrownBy(() -> followQueryService.getFollowers(VIEWER, TARGET, null, 0, 20))
			.isInstanceOf(FollowException.class)
			.extracting("errorCode").isEqualTo(FollowErrorCode.FOLLOW_TARGET_NOT_FOUND);
		then(memberFollowRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("보는 사람과 차단 관계면 FOLLOW403_BLOCKED. 내 목록은 차단을 보지 않는다")
	void blocked() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET, "대상")));
		given(memberBlockRepository.existsBetween(VIEWER, TARGET)).willReturn(true);

		assertThatThrownBy(() -> followQueryService.getFollowings(VIEWER, TARGET, null, 0, 20))
			.isInstanceOf(FollowException.class)
			.extracting("errorCode").isEqualTo(FollowErrorCode.FOLLOW_BLOCKED);

		given(socialMemberReader.findSocial(VIEWER)).willReturn(Optional.of(member(VIEWER, "나")));
		given(memberFollowRepository.findFollowings(eq(VIEWER), eq(VIEWER), eq(""), any()))
			.willReturn(new SliceImpl<>(List.of(), Pageable.ofSize(20), false));
		followQueryService.getFollowings(VIEWER, VIEWER, null, 0, 20);
		then(memberBlockRepository).should(never()).existsBetween(VIEWER, VIEWER);
	}
}
