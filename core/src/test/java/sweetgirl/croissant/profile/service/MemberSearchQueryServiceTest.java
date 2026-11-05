package sweetgirl.croissant.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberSearchQueryService")
class MemberSearchQueryServiceTest {

	@Mock
	private MemberRepository memberRepository;
	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private sweetgirl.croissant.follow.repository.MemberFollowRequestRepository memberFollowRequestRepository;
	@Mock
	private CharacterFaceReader characterFaceReader;

	@InjectMocks
	private MemberSearchQueryService memberSearchQueryService;

	private Member member(Long id, String nickname) {
		Member member = Member.builder().nickname(nickname).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	@Test
	@DisplayName("검색어를 정규화해 앞글자로 찾고 줄마다 팔로우 관계를 붙인다")
	void search() {
		given(memberRepository.searchSocialByNicknamePrefix(eq("빵순"), eq(1L), any(), any()))
			.willReturn(List.of(member(10L, "빵순이"), member(11L, "빵순찌")));
		given(memberFollowRepository.findFolloweeIdsIn(1L, List.of(10L, 11L))).willReturn(List.of(10L));
		given(memberFollowRepository.findFollowerIdsIn(1L, List.of(10L, 11L))).willReturn(List.of());
		given(characterFaceReader.facesOf(List.of(10L, 11L)))
			.willReturn(Map.of(10L, new CharacterFace("CROISSANT", "world/croissant.png")));

		List<FollowMemberResponse> result = memberSearchQueryService.search(1L, " 빵 순 ");

		assertThat(result).containsExactly(
			new FollowMemberResponse(10L, "빵순이", true, false, "CROISSANT", "world/croissant.png", false, false),
			new FollowMemberResponse(11L, "빵순찌", false, false, null, null, false, false));
	}

	@Test
	@DisplayName("정규화하고 2자 미만이면 조회하지 않고 빈 목록")
	void tooShort() {
		assertThat(memberSearchQueryService.search(1L, "빵 ")).isEmpty();
		assertThat(memberSearchQueryService.search(1L, null)).isEmpty();
		then(memberRepository).shouldHaveNoInteractions();
	}
}
