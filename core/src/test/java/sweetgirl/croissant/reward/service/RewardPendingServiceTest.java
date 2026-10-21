package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.entity.MemberPendingReward;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardItemType;
import sweetgirl.croissant.reward.repository.MemberPendingRewardRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

@ExtendWith(MockitoExtension.class)
class RewardPendingServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private MemberPendingRewardRepository pendingRewardRepository;

	private RewardPendingService rewardPendingService;

	@BeforeEach
	void setUp() {
		rewardPendingService = new RewardPendingService(pendingRewardRepository, new ObjectMapper());
	}

	@Test
	@DisplayName("남긴 보상은 같은 모양으로, id 를 달고 읽힌다")
	void roundTrip() {
		RewardItemResponse reward = RewardItemResponse.crumbs(
			new GrantResult(Outcome.GRANTED, 100, 20, 120), CrumbReason.INVITE_INVITER);
		rewardPendingService.add(MEMBER_ID, "invite:9:inviter", reward);
		ArgumentCaptor<MemberPendingReward> saved = ArgumentCaptor.forClass(MemberPendingReward.class);
		then(pendingRewardRepository).should().save(saved.capture());
		ReflectionTestUtils.setField(saved.getValue(), "id", 31L);
		given(pendingRewardRepository.findByMemberIdOrderByIdAsc(MEMBER_ID)).willReturn(List.of(saved.getValue()));

		List<RewardItemResponse> pending = rewardPendingService.list(MEMBER_ID);

		assertThat(pending).containsExactly(reward.withId(31L));
		assertThat(pending.get(0).type()).isEqualTo(RewardItemType.CRUMBS);
	}

	@Test
	@DisplayName("같은 키로 이미 남겼으면 또 쌓지 않는다")
	void skipsDuplicate() {
		given(pendingRewardRepository.existsByMemberIdAndDedupeKey(MEMBER_ID, "level_up:3")).willReturn(true);

		rewardPendingService.add(MEMBER_ID, "level_up:3", new RewardItemResponse(
			null, RewardItemType.LEVEL_UP, null, 2, 3, null, null, "earth", "space", null, null));

		then(pendingRewardRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("확인하면 내 것만 지운다")
	void acknowledge() {
		rewardPendingService.acknowledge(MEMBER_ID, List.of(31L, 32L));

		then(pendingRewardRepository).should().deleteByMemberIdAndIdIn(MEMBER_ID, List.of(31L, 32L));
	}

	@Test
	@DisplayName("엔티티는 만든 때를 남긴다")
	void entityKeepsCreatedAt() {
		LocalDateTime now = LocalDateTime.of(2026, 10, 12, 9, 0);

		assertThat(MemberPendingReward.of(MEMBER_ID, "level_up:3", "{}", now).getCreatedAt()).isEqualTo(now);
	}
}
