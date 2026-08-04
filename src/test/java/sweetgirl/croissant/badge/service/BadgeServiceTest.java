package sweetgirl.croissant.badge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.badge.dto.response.MemberBadgeResponse;
import sweetgirl.croissant.badge.entity.Badge;
import sweetgirl.croissant.badge.entity.MemberBadge;
import sweetgirl.croissant.badge.enums.ConditionType;
import sweetgirl.croissant.badge.repository.BadgeRepository;
import sweetgirl.croissant.badge.repository.MemberBadgeRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
class BadgeServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	BadgeRepository badgeRepository;
	@Mock
	MemberBadgeRepository memberBadgeRepository;
	@Mock
	MemberConditionEvaluator conditionEvaluator;

	@InjectMocks
	BadgeService badgeService;

	@BeforeEach
	void setUp() {
		org.springframework.test.util.ReflectionTestUtils.setField(
			badgeService, "cdnBaseUrl", "https://cdn.example.com/");
	}

	@Test
	void getBadges_awardsNewlySatisfiedBadgeAndReturnsIt() {
		Badge badge = badge(1L, "regular", "badges/regular.png", ConditionType.STORE_REVISIT, 3);
		MemberBadge award = memberBadge(badge, LocalDateTime.of(2026, 8, 4, 12, 0));
		MemberConditionSnapshot snapshot = new MemberConditionSnapshot(0, 0, 3, 0, 3);
		given(badgeRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(badge));
		given(memberBadgeRepository.findAllByMemberId(7L))
			.willReturn(List.of(), List.of(award));
		given(conditionEvaluator.snapshot(7L)).willReturn(snapshot);
		given(conditionEvaluator.evaluate(ConditionType.STORE_REVISIT, 3, snapshot))
			.willReturn(new ConditionProgress(3, 3));

		List<MemberBadgeResponse> result = badgeService.getBadges(7L);

		then(memberBadgeRepository).should().awardIfAbsent(7L, 1L);
		assertThat(result).singleElement().satisfies(response -> {
			assertThat(response.achieved()).isTrue();
			assertThat(response.celebrated()).isFalse();
			assertThat(response.imageUrl()).isEqualTo("https://cdn.example.com/badges/regular.png");
		});
	}

	@Test
	void getBadges_doesNotReevaluateOrRemoveExistingAward() {
		Badge badge = badge(1L, "regular", null, ConditionType.STORE_REVISIT, 3);
		MemberBadge award = memberBadge(badge, LocalDateTime.now());
		given(badgeRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(badge));
		given(memberBadgeRepository.findAllByMemberId(7L)).willReturn(List.of(award));

		List<MemberBadgeResponse> result = badgeService.getBadges(7L);

		then(memberBadgeRepository).should(never()).awardIfAbsent(any(), any());
		then(conditionEvaluator).should(never()).evaluate(any(), any(), any());
		assertThat(result.get(0).achieved()).isTrue();
	}

	@Test
	void celebrate_isIdempotent() {
		Badge badge = badge(1L, "welcome", null, ConditionType.STARTER_ALL, null);
		MemberBadge award = memberBadge(badge, LocalDateTime.now());
		given(badgeRepository.findByCode("welcome")).willReturn(Optional.of(badge));
		given(memberBadgeRepository.findByMemberIdAndBadgeCode(7L, "welcome")).willReturn(Optional.of(award));

		badgeService.celebrate(7L, "welcome");
		LocalDateTime firstCelebratedAt = award.getCelebratedAt();
		badgeService.celebrate(7L, "welcome");

		assertThat(award.getCelebratedAt()).isEqualTo(firstCelebratedAt);
	}

	private Badge badge(Long id, String code, String imageKey, ConditionType conditionType, Integer threshold) {
		Badge badge = Badge.create(code, imageKey, code, "hint", conditionType, threshold, 1);
		org.springframework.test.util.ReflectionTestUtils.setField(badge, "id", id);
		return badge;
	}

	private MemberBadge memberBadge(Badge badge, LocalDateTime achievedAt) {
		Member member = org.mockito.Mockito.mock(Member.class);
		given(member.getId()).willReturn(7L);
		return MemberBadge.achieved(member, badge, achievedAt);
	}
}
