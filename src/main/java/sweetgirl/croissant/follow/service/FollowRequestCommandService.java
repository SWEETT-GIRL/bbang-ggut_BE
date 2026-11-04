package sweetgirl.croissant.follow.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.service.CommunityNotificationService;
import sweetgirl.croissant.follow.code.FollowErrorCode;
import sweetgirl.croissant.follow.exception.FollowException;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRequestRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.SocialMemberReader;

/** 비공개 계정이 받은 팔로우 요청을 수락 · 거절한다. 요청을 보내는 쪽은 {@link FollowCommandService#follow} 다. */
@Service
@RequiredArgsConstructor
@Transactional
public class FollowRequestCommandService {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MemberFollowRepository memberFollowRepository;
	private final MemberFollowRequestRepository memberFollowRequestRepository;
	private final MemberReader memberReader;
	private final SocialMemberReader socialMemberReader;
	private final CommunityNotificationService communityNotificationService;

	/**
	 * 요청을 수락한다 — 요청한 사람이 나를 팔로우하게 되고 요청은 지운다. 요청한 사람에게 "수락했어요" 알림이 가고,
	* 내 알림함의 "요청했어요"는 거둔다.
	 * 요청이 없으면 404 FOLLOW404_REQUEST, 요청한 사람이 그새 탈퇴했으면 요청만 지우고 404.
	 */
	public void accept(Long memberId, Long requesterId) {
		memberReader.requireLoggedIn(memberId);
		if (!memberFollowRequestRepository.existsByIdRequesterIdAndIdTargetId(requesterId, memberId)) {
			throw new FollowException(FollowErrorCode.FOLLOW_REQUEST_NOT_FOUND);
		}
		memberFollowRequestRepository.deleteByPair(requesterId, memberId);
		// 수락했으니 내 알림함의 "요청했어요"는 거둔다 — 남겨 두면 눌러도 없는 요청이고, 같은 사람이 다시 요청하면
		// 유니크 제약에 걸려 옛 시각의 줄이 그대로 보인다
		communityNotificationService.retractFollowRequestNotification(requesterId, memberId);
		Member requester = socialMemberReader.findSocial(requesterId)
			.orElseThrow(() -> new FollowException(FollowErrorCode.FOLLOW_REQUEST_NOT_FOUND));
		if (memberFollowRepository.insertIfAbsent(requesterId, memberId, LocalDateTime.now(KST)) == 1) {
			communityNotificationService.notifyFollowAccepted(memberReader.getMember(memberId), requester);
		}
	}

	/** 요청을 거절한다 — 요청만 지우고 요청한 사람에게는 알리지 않는다(내 알림함의 "요청했어요"는 거둔다). 없으면 404. */
	public void reject(Long memberId, Long requesterId) {
		memberReader.requireLoggedIn(memberId);
		if (memberFollowRequestRepository.deleteByPair(requesterId, memberId) == 0) {
			throw new FollowException(FollowErrorCode.FOLLOW_REQUEST_NOT_FOUND);
		}
		communityNotificationService.retractFollowRequestNotification(requesterId, memberId);
	}

	/**
	 * 받은 요청을 전부 수락한다 — 계정을 공개로 돌릴 때. 요청한 사람들은 이제 누구나처럼 바로 팔로우할 수 있었을 사람이니
	 * 조용히 팔로워로 만들고 알림은 보내지 않는다. 내 알림함의 "요청했어요"는 거둔다.
	 */
	public void acceptAllOf(Long memberId) {
		List<Long> requesterIds = memberFollowRequestRepository.findRequesterIdsByTargetId(memberId);
		if (requesterIds.isEmpty()) {
			return;
		}
		LocalDateTime now = LocalDateTime.now(KST);
		for (Long requesterId : requesterIds) {
			memberFollowRepository.insertIfAbsent(requesterId, memberId, now);
			// 자동 수락도 알림함의 "요청했어요"는 거둔다 — 수락과 같은 이유
			communityNotificationService.retractFollowRequestNotification(requesterId, memberId);
		}
		memberFollowRequestRepository.deleteAllByTargetId(memberId);
	}
}
