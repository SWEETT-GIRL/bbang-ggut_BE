package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.enums.MemberRestrictionType;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;

@Component
@RequiredArgsConstructor
public class MemberRestrictionReader {

	private static final List<MemberRestrictionType> WRITE_BLOCKING_TYPES = List.of(
		MemberRestrictionType.WRITE_RESTRICTED,
		MemberRestrictionType.SUSPENDED,
		MemberRestrictionType.BANNED);

	private final MemberRestrictionRepository memberRestrictionRepository;

	public void validateCanWrite(Long memberId) {
		if (memberId == null) {
			throw new MemberException(MemberErrorCode.UNAUTHORIZED);
		}
		if (!memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
			memberId,
			WRITE_BLOCKING_TYPES,
			LocalDateTime.now()).isEmpty()) {
			throw new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED);
		}
	}
}
