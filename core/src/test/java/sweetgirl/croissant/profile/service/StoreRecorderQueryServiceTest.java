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

import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreRecorderQueryService")
class StoreRecorderQueryServiceTest {

	private static final Long VIEWER = 1L;
	private static final Long STORE = 10L;

	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private MemberSearchQueryService memberSearchQueryService;

	@InjectMocks
	private StoreRecorderQueryService storeRecorderQueryService;

	private Member member(Long id) {
		Member member = Member.builder().nickname("회원" + id).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private void givenRelationsPassThrough() {
		given(memberSearchQueryService.withRelations(any(), any())).willAnswer(invocation -> {
			List<Member> people = invocation.getArgument(1);
			return people.stream().map(person -> FollowMemberResponse.of(person, false, false)).toList();
		});
	}

	@Test
	@DisplayName("최근 기록순 후보에서 이미 팔로우 중 · 차단 관계를 빼고 limit 만큼, 순서를 지킨다")
	void recorders() {
		given(breadRecordRepository.findPublicRecorderIdsByStore(STORE, VIEWER, 4))
			.willReturn(List.of(10L, 11L, 12L, 13L));
		given(memberFollowRepository.findFolloweeIdsIn(VIEWER, List.of(10L, 11L, 12L, 13L))).willReturn(List.of(11L));
		given(memberBlockRepository.existsBetween(VIEWER, 10L)).willReturn(false);
		given(memberBlockRepository.existsBetween(VIEWER, 12L)).willReturn(true);
		given(memberBlockRepository.existsBetween(VIEWER, 13L)).willReturn(false);
		given(memberRepository.findSocialByIdIn(List.of(10L, 13L))).willReturn(List.of(member(13L), member(10L)));
		givenRelationsPassThrough();

		List<FollowMemberResponse> result = storeRecorderQueryService.recorders(VIEWER, STORE, 2);

		assertThat(result).extracting(FollowMemberResponse::memberId).containsExactly(10L, 13L);
	}

	@Test
	@DisplayName("게스트(보는 사람 없음)도 본다 — 본인 제외 id 는 0, 팔로우 · 차단은 보지 않는다")
	void guestViewer() {
		given(breadRecordRepository.findPublicRecorderIdsByStore(STORE, 0L, 10)).willReturn(List.of(10L));
		given(memberRepository.findSocialByIdIn(List.of(10L))).willReturn(List.of(member(10L)));
		givenRelationsPassThrough();

		List<FollowMemberResponse> result = storeRecorderQueryService.recorders(null, STORE, 5);

		assertThat(result).extracting(FollowMemberResponse::memberId).containsExactly(10L);
		then(memberFollowRepository).shouldHaveNoInteractions();
		then(memberBlockRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("limit 은 1 ~ 20 으로 자르고, 후보가 없으면 사람을 읽지 않는다")
	void clampsLimitAndEmpty() {
		given(breadRecordRepository.findPublicRecorderIdsByStore(eq(STORE), eq(VIEWER), eq(40))).willReturn(List.of());

		assertThat(storeRecorderQueryService.recorders(VIEWER, STORE, 100)).isEmpty();
		then(memberRepository).shouldHaveNoInteractions();
	}
}
