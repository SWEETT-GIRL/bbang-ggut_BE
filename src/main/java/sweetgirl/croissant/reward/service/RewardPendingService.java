package sweetgirl.croissant.reward.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.entity.MemberPendingReward;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.MemberPendingRewardRepository;

/** 앱이 아직 연출하지 못한 보상을 쌓고 읽는다. */
@Service
@RequiredArgsConstructor
@Transactional
public class RewardPendingService {

	private final MemberPendingRewardRepository pendingRewardRepository;
	private final ObjectMapper objectMapper;

	/**
	 * 연출할 보상을 남긴다. 같은 키로 이미 남겼으면 넘어간다.
	 *
	 * <p>적립과 같은 트랜잭션에서 부른다 — 그 회원의 지갑이 잠겨 있어, 같은 키가 나란히 들어오지 않는다.
	 */
	public void add(Long memberId, String dedupeKey, RewardItemResponse reward) {
		if (pendingRewardRepository.existsByMemberIdAndDedupeKey(memberId, dedupeKey)) {
			return;
		}
		pendingRewardRepository.save(
			MemberPendingReward.of(memberId, dedupeKey, write(reward), LocalDateTime.now()));
	}

	@Transactional(readOnly = true)
	public List<RewardItemResponse> list(Long memberId) {
		return pendingRewardRepository.findByMemberIdOrderByIdAsc(memberId).stream()
			.map(pending -> read(pending.getPayload()).withId(pending.getId()))
			.toList();
	}

	/** 앱이 연출을 끝낸 것을 지운다. */
	public void acknowledge(Long memberId, List<Long> ids) {
		pendingRewardRepository.deleteByMemberIdAndIdIn(memberId, ids);
	}

	private String write(RewardItemResponse reward) {
		try {
			return objectMapper.writeValueAsString(reward);
		} catch (JsonProcessingException exception) {
			throw new RewardException(RewardErrorCode.REWARD_PENDING_PAYLOAD_INVALID);
		}
	}

	private RewardItemResponse read(String payload) {
		try {
			return objectMapper.readValue(payload, RewardItemResponse.class);
		} catch (JsonProcessingException exception) {
			throw new RewardException(RewardErrorCode.REWARD_PENDING_PAYLOAD_INVALID);
		}
	}
}
