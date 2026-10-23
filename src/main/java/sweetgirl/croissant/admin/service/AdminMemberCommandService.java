package sweetgirl.croissant.admin.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;
import sweetgirl.croissant.abuse.repository.ModerationActionRepository;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminMemberBanRequest;
import sweetgirl.croissant.admin.dto.request.AdminMemberRestrictionRequest;
import sweetgirl.croissant.admin.dto.request.AdminProfileResetRequest;
import sweetgirl.croissant.admin.dto.response.AdminMemberBanResponse;
import sweetgirl.croissant.admin.dto.response.AdminMemberRestrictionResponse;
import sweetgirl.croissant.admin.dto.response.AdminProfileResetResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberRestriction;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.enums.MemberRestrictionType;
import sweetgirl.croissant.member.event.MemberWithdrawnEvent;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.member.service.MemberReader;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminMemberCommandService {
	private static final String DEFAULT_RESET_NICKNAME = "빵긋 사용자";

	private final MemberReader memberReader;
	private final MemberRestrictionRepository memberRestrictionRepository;
	private final ModerationActionRepository moderationActionRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final FcmTokenRepository fcmTokenRepository;
	private final ApplicationEventPublisher eventPublisher;

	public AdminMemberBanResponse banMember(Long memberId, AdminMemberBanRequest request) {

		Member member = memberReader.getMember(memberId);

		boolean alreadyBanned = !memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(

			memberId,
			List.of(MemberRestrictionType.BANNED),
			LocalDateTime.now()).isEmpty();
		if (alreadyBanned) {
			throw new AdminException(AdminErrorCode.MEMBER_ALREADY_BANNED);
		}

		LocalDateTime now = LocalDateTime.now();

		//멤버 활동 제한 현재상태 테이블
		MemberRestriction restriction = memberRestrictionRepository.save(
			new MemberRestriction(member, MemberRestrictionType.BANNED, request.normalizedReason(), now, null));

		member.softDelete();
		// 탈퇴와 같은 정리 — 닉네임 · 이메일을 비우고, 팔로우 · 좋아요 · 톡 · 멘션 · 태그를 각 도메인이 지운다(팔로워 수와 목록이 어긋나지 않게)
		member.anonymizePersonalInfo();
		eventPublisher.publishEvent(new MemberWithdrawnEvent(memberId));

		List<RefreshToken> refreshTokens = refreshTokenRepository.findAllByMember(member);
		refreshTokens.forEach(RefreshToken::revoke);
		fcmTokenRepository.deleteAllByMember(member);

		//어떤 사유로 제재조치를 내렸는가
		ModerationAction action = moderationActionRepository.save(
			new ModerationAction(
				null,
				ModerationTargetType.MEMBER_RESTRICTION,
				restriction.getId(),
				member,
				AbuseReportAction.BAN_MEMBER,
				request.normalizedReason()));

		return AdminMemberBanResponse.of(member, restriction, action);
	}

	public AdminProfileResetResponse resetProfile(Long memberId, AdminProfileResetRequest request) {
		Member member = memberReader.getMember(memberId);

		// 살아 있는 회원끼리 닉네임이 유일해야 해서(uq_member_nickname_normalized_alive) 번호를 붙인다
		member.resetNickname(DEFAULT_RESET_NICKNAME + memberId);
		ModerationAction action = moderationActionRepository.save(request.toModerationAction(member));
		return AdminProfileResetResponse.of(member, action);
	}

	public AdminMemberRestrictionResponse createRestriction(
			Long memberId,
			AdminMemberRestrictionRequest request) {
		Member member = memberReader.getMember(memberId);
		MemberRestrictionType type = toRestrictionType(request.type());
		LocalDateTime startsAt = LocalDateTime.now();
		LocalDateTime endsAt = calculateEndsAt(startsAt, request.durationDays());

		MemberRestriction restriction = memberRestrictionRepository.save(
			request.toRestriction(member, type, startsAt, endsAt));
		AbuseReportAction actionType = toMemberRestrictionAction(type);
		if (actionType == null) {
			return AdminMemberRestrictionResponse.from(restriction);
		}
		ModerationAction action = moderationActionRepository.save(
			request.toModerationAction(member, restriction, actionType));
		return AdminMemberRestrictionResponse.of(restriction, action);
	}

	private MemberRestrictionType toRestrictionType(String type) {
		if (type == null || type.isBlank()) {
			throw new AdminException(AdminErrorCode.MEMBER_RESTRICTION_INVALID_TYPE);
		}
		try {
			MemberRestrictionType parsed = MemberRestrictionType.valueOf(type.trim().toUpperCase(Locale.ROOT));
			if (parsed == MemberRestrictionType.BANNED) {
				throw new AdminException(AdminErrorCode.MEMBER_RESTRICTION_INVALID_TYPE);
			}
			return parsed;
		} catch (IllegalArgumentException e) {
			throw new AdminException(AdminErrorCode.MEMBER_RESTRICTION_INVALID_TYPE);
		}
	}

	private LocalDateTime calculateEndsAt(LocalDateTime startsAt, Integer durationDays) {
		if (durationDays == null) {
			return null;
		}
		if (durationDays < 1) {
			throw new AdminException(AdminErrorCode.MEMBER_RESTRICTION_INVALID_DURATION);
		}
		return startsAt.plusDays(durationDays);
	}

	private AbuseReportAction toMemberRestrictionAction(MemberRestrictionType type) {
		return switch (type) {
			case WRITE_RESTRICTED, PROFILE_RESTRICTED -> AbuseReportAction.RESTRICT_MEMBER;
			case SUSPENDED -> AbuseReportAction.SUSPEND_MEMBER;
			case BANNED -> AbuseReportAction.BAN_MEMBER;
			case WARNING -> null;
		};
	}

}
