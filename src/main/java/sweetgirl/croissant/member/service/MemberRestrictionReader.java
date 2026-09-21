package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
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
	 * 게스트 쓰기를 실제로 막을지. <b>기본은 끔</b>이다.
	 *
	 * <p>막는 코드와 켜는 시점을 갈라 두기 위해서다. 스토어에 나가 있는 앱은 이 403 을 받아도
	 * 무슨 일인지 말해 주지 못하므로, 서버가 먼저 막으면 쓰던 사람이 이유 없이 실패를 본다.
	 * 새 앱이 스토어에 깔리고 강제 업데이트가 켜진 뒤에 이 값을 켠다.
	 */
	@Value("${guest.write-blocked:false}")
	private boolean guestWriteBlocked;

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
		if (guestWriteBlocked && memberReader.getMember(memberId).isGuest()) {
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
