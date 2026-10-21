package sweetgirl.croissant.follow.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.follow.code.FollowErrorCode;
import sweetgirl.croissant.follow.exception.FollowException;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.member.service.SocialMemberReader;

@Service
@RequiredArgsConstructor
@Transactional
public class FollowCommandService {

	/** 하루(KST)에 팔로우할 수 있는 사람 수. 팔로우/언팔 반복으로 알림을 도배하는 걸 막는다. */
	static final int DAILY_FOLLOW_LIMIT = 100;

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MemberFollowRepository memberFollowRepository;
	private final MemberBlockRepository memberBlockRepository;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final SocialMemberReader socialMemberReader;

	/**
	 * 팔로우한다. 이미 하고 있으면 그대로 200 이다(멱등).
	 *
	 * <p>게스트는 403(MEMBER403_GUEST), 정지 · 강퇴는 403, 나 자신은 400, 상대가 없거나 게스트 · 봇 · 탈퇴면 404,
	 * 어느 쪽이든 차단했으면 403, 오늘 {@value #DAILY_FOLLOW_LIMIT} 명을 넘기면 429.
	 */
	public void follow(Long memberId, Long targetId) {
		memberReader.requireLoggedIn(memberId);
		memberRestrictionReader.validateCanInteract(memberId);
		if (memberId.equals(targetId)) {
			throw new FollowException(FollowErrorCode.FOLLOW_SELF);
		}
		Member target = socialMemberReader.findSocial(targetId)
			.orElseThrow(() -> new FollowException(FollowErrorCode.FOLLOW_TARGET_NOT_FOUND));
		if (memberBlockRepository.existsBetween(memberId, target.getId())) {
			throw new FollowException(FollowErrorCode.FOLLOW_BLOCKED);
		}
		if (memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(memberId, target.getId())) {
			return;
		}
		LocalDateTime now = LocalDateTime.now(KST);
		LocalDateTime startOfToday = LocalDate.now(KST).atStartOfDay();
		if (memberFollowRepository.countByIdFollowerIdAndCreatedAtGreaterThanEqual(memberId,
			startOfToday) >= DAILY_FOLLOW_LIMIT) {
			throw new FollowException(FollowErrorCode.FOLLOW_DAILY_LIMIT);
		}
		memberFollowRepository.insertIfAbsent(memberId, target.getId(), now);
	}

	/** 팔로우를 끊는다. 안 하고 있었어도 200 이다. 정지 중이어도 끊는 건 된다 — 남에게 닿는 행동이 아니다. */
	public void unfollow(Long memberId, Long targetId) {
		memberReader.requireLoggedIn(memberId);
		memberFollowRepository.deleteByPair(memberId, targetId);
	}

	/**
	 * 두 사람이 서로 팔로우하게 한다 — 초대 코드 수락 · 빵투어 일행 합류 때.
	 *
	 * <p>하루 상한을 세지 않는다(사용자가 누른 게 아니다). 같은 사람 · 둘 중 하나가 상대가 될 수 없는 사람 ·
	 * 차단 관계면 조용히 건너뛴다. 부르는 쪽 흐름(초대 · 합류)을 팔로우 때문에 실패시키지 않는다.
	 */
	public void followEachOther(Long memberId, Long otherId) {
		if (memberId == null || otherId == null || memberId.equals(otherId)) {
			return;
		}
		if (socialMemberReader.findSocial(memberId).isEmpty() || socialMemberReader.findSocial(otherId).isEmpty()) {
			return;
		}
		if (memberBlockRepository.existsBetween(memberId, otherId)) {
			return;
		}
		LocalDateTime now = LocalDateTime.now(KST);
		memberFollowRepository.insertIfAbsent(memberId, otherId, now);
		memberFollowRepository.insertIfAbsent(otherId, memberId, now);
	}
}
