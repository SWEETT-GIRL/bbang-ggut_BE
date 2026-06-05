package sweetgirl.croissant.admin.service;

import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminMemberRestrictionRequest;
import sweetgirl.croissant.admin.dto.request.AdminProfileResetRequest;
import sweetgirl.croissant.admin.dto.response.AdminMemberRestrictionResponse;
import sweetgirl.croissant.admin.dto.response.AdminProfileResetResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberRestriction;
import sweetgirl.croissant.member.enums.MemberRestrictionType;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.repository.ModerationActionRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMemberService {

	private static final String DEFAULT_RESET_NICKNAME = "빵긋 사용자";

	private final MemberReader memberReader;
	private final MemberRestrictionRepository memberRestrictionRepository;
	private final ModerationActionRepository moderationActionRepository;

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
		return AdminMemberRestrictionResponse.from(restriction);
	}

	private MemberRestrictionType toRestrictionType(String type) {
		if (type == null || type.isBlank()) {
			throw new AdminException(AdminErrorCode.MEMBER_RESTRICTION_INVALID_TYPE);
		}
		try {
			return MemberRestrictionType.valueOf(type.trim().toUpperCase(Locale.ROOT));
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

}
