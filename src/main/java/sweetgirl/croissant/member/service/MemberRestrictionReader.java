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
	private final MemberReader memberReader;

	/**
	 * 쓸 자격이 있는가 — 게스트가 아니고, 제재 중도 아닌가.
	 *
	 * <p>게스트를 여기서 막는 이유: 쓰기 경로마다 따로 검사하면 새 기능이 늘 때 빠뜨린다.
	 * 저장·기록·만들기 경로는 전부 이 문을 지나므로, 여기 한 곳이면 새 경로도 기본으로 막힌다.
	 * 반대로 로그인·알림 설정·약관 동의·닉네임·탈퇴는 이 문을 안 지나며, 게스트에게도 열려 있어야 한다.
	 *
	 * <p><b>401 이 아니라 403 이다.</b> 앱 apiClient 는 401 을 받으면 게스트 세션을 자동
	 * 재발급하므로, 401 로 막으면 같은 요청이 끝없이 되풀이된다.
	 */
	public void validateCanWrite(Long memberId) {
		if (memberId == null) {
			throw new MemberException(MemberErrorCode.UNAUTHORIZED);
		}
		if (memberReader.getMember(memberId).isGuest()) {
			throw new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED);
		}
		if (!memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
			memberId,
			WRITE_BLOCKING_TYPES,
			LocalDateTime.now()).isEmpty()) {
			throw new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED);
		}
	}
}
