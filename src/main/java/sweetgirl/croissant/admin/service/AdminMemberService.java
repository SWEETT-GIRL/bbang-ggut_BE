package sweetgirl.croissant.admin.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
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
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.enums.ModerationTargetType;
import sweetgirl.croissant.report.enums.UserReportAction;
import sweetgirl.croissant.report.repository.ModerationActionRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMemberService {
	private static final String DEFAULT_RESET_NICKNAME = "빵긋 사용자";

	private final MemberReader memberReader;
	private final MemberRestrictionRepository memberRestrictionRepository;
	private final ModerationActionRepository moderationActionRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final FcmTokenRepository fcmTokenRepository;

	@Transactional
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
				UserReportAction.BAN_MEMBER,
				request.normalizedReason()));

		return AdminMemberBanResponse.of(member, restriction, action);
	}

	@Transactional
	public AdminProfileResetResponse resetProfile(Long memberId, AdminProfileResetRequest request) {
		Member member = memberReader.getMember(memberId);

		member.resetNickname(DEFAULT_RESET_NICKNAME);
		ModerationAction action = moderationActionRepository.save(request.toModerationAction(member));
		return AdminProfileResetResponse.of(member, action);
	}

	@Transactional
	public AdminMemberRestrictionResponse createRestriction(
			Long memberId,
			AdminMemberRestrictionRequest request) {
		Member member = memberReader.getMember(memberId);
		MemberRestrictionType type = toRestrictionType(request.type());
		LocalDateTime startsAt = LocalDateTime.now();
		LocalDateTime endsAt = calculateEndsAt(startsAt, request.durationDays());

		MemberRestriction restriction = memberRestrictionRepository.save(
			request.toRestriction(member, type, startsAt, endsAt));
		UserReportAction actionType = toMemberRestrictionAction(type);
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

	private UserReportAction toMemberRestrictionAction(MemberRestrictionType type) {
		return switch (type) {
			case WRITE_RESTRICTED, PROFILE_RESTRICTED -> UserReportAction.RESTRICT_MEMBER;
			case SUSPENDED -> UserReportAction.SUSPEND_MEMBER;
			case BANNED -> UserReportAction.BAN_MEMBER;
			case WARNING -> null;
		};
	}

}
