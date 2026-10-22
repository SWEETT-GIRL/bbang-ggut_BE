package sweetgirl.croissant.pat.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.service.CommunityNotificationService;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.pat.code.PatErrorCode;
import sweetgirl.croissant.pat.exception.PatException;
import sweetgirl.croissant.pat.repository.MemberPatRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class PatCommandService {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MemberPatRepository memberPatRepository;
	private final MemberBlockRepository memberBlockRepository;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final SocialMemberReader socialMemberReader;
	private final CommunityNotificationService communityNotificationService;

	/**
	 * 톡을 친다 — 사람마다 하루 1번(KST). 오늘 이미 쳤으면 아무것도 안 하고 200(토스트 없음).
	 *
	 * <p>게스트 403(MEMBER403_GUEST), 작성 제한 · 정지 403(남에게 닿는 행동이라 팔로우와 달리 작성 제한도 막는다),
	 * 나 자신 400, 상대가 없거나 게스트 · 봇 · 탈퇴면 404, 어느 쪽이든 차단했으면 403.
	 */
	public void pat(Long memberId, Long targetId) {
		memberRestrictionReader.validateCanWrite(memberId);
		if (memberId.equals(targetId)) {
			throw new PatException(PatErrorCode.PAT_SELF);
		}
		Member target = socialMemberReader.findSocial(targetId)
			.orElseThrow(() -> new PatException(PatErrorCode.PAT_TARGET_NOT_FOUND));
		if (memberBlockRepository.existsBetween(memberId, target.getId())) {
			throw new PatException(PatErrorCode.PAT_BLOCKED);
		}
		LocalDateTime now = LocalDateTime.now(KST);
		if (memberPatRepository.insertIfAbsent(memberId, target.getId(), LocalDate.now(KST), now) == 1) {
			communityNotificationService.notifyPat(memberReader.getMember(memberId), target);
		}
	}
}
