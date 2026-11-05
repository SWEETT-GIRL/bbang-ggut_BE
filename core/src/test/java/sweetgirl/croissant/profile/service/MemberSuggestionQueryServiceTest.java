package sweetgirl.croissant.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

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
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberSuggestionQueryService")
class MemberSuggestionQueryServiceTest {

	private static final Long VIEWER = 1L;

	@Mock
	private MemberSuggestionCandidateReader candidateReader;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberSearchQueryService memberSearchQueryService;

	@InjectMocks
	private MemberSuggestionQueryService memberSuggestionQueryService;

	private Member member(Long id) {
		Member member = Member.builder().nickname("회원" + id).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	@Test
	@DisplayName("후보에서 이미 팔로우 중 · 차단 관계를 빼고 3명, 후보 순서(같은 빵집 우선)를 지킨다")
	void suggests() {
		given(candidateReader.candidateIds(VIEWER))
			.willReturn(List.of(10L, 11L, 12L, 13L, 14L));
		given(memberFollowRepository.findFolloweeIdsIn(VIEWER, List.of(10L, 11L, 12L, 13L, 14L)))
			.willReturn(List.of(11L));
		given(memberBlockRepository.existsBetween(VIEWER, 12L)).willReturn(true);
		given(memberBlockRepository.existsBetween(eq(VIEWER), eq(10L))).willReturn(false);
		given(memberBlockRepository.existsBetween(eq(VIEWER), eq(13L))).willReturn(false);
		given(memberBlockRepository.existsBetween(eq(VIEWER), eq(14L))).willReturn(false);
		// 쿼리 결과 순서는 뒤섞여 올 수 있다
		given(memberRepository.findSocialByIdIn(List.of(10L, 13L, 14L)))
			.willReturn(List.of(member(14L), member(10L), member(13L)));
		given(memberSearchQueryService.withRelations(eq(VIEWER), any())).willAnswer(invocation -> {
			List<Member> people = invocation.getArgument(1);
			return people.stream().map(person -> FollowMemberResponse.of(person, false, false)).toList();
		});

		List<FollowMemberResponse> result = memberSuggestionQueryService.suggest(VIEWER);

		assertThat(result).extracting(FollowMemberResponse::memberId).containsExactly(10L, 13L, 14L);
	}

	@Test
	@DisplayName("후보가 없으면 빈 목록이고 사람을 읽지 않는다")
	void empty() {
		given(candidateReader.candidateIds(VIEWER)).willReturn(List.of());

		assertThat(memberSuggestionQueryService.suggest(VIEWER)).isEmpty();
		then(memberRepository).shouldHaveNoInteractions();
	}
}
