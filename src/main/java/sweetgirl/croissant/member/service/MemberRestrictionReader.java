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
	 * <p>이 문을 지나는 쓰기는 빵 기록·리뷰·메뉴 등록·가게 계획·코스 메모·코스 초대/합류/진행뿐이다(전부가 아니다).
	 * 세계관 쓰기(룰렛·티켓 깨우기·친구 고르기)는 제재 없이 게스트 스위치만 보는 {@link #validateNotGuest} 를 부른다.
	 * 찜·코스 만들기/담기는 스위치와 상관없이 {@link MemberReader#requireLoggedIn} 이 늘 막는다.
	 * 새 쓰기 경로는 저절로 막히지 않는다 — 만들 때 둘 중 하나를 골라 부른다.
	 * 로그인·알림 설정·약관 동의·닉네임·탈퇴는 어느 쪽도 안 지나며, 게스트에게도 열려 있어야 한다.
	 *
	 * <p><b>401 이 아니라 403 이다.</b> 앱 apiClient 는 401 을 받으면 게스트 세션을 자동
	 * 재발급하므로, 401 로 막으면 같은 요청이 끝없이 되풀이된다.
	 */
	public void validateCanWrite(Long memberId) {
		if (memberId == null) {
			throw new MemberException(MemberErrorCode.UNAUTHORIZED);
		}
		validateNotGuest(memberId);
		if (!memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
			memberId,
			WRITE_BLOCKING_TYPES,
			LocalDateTime.now()).isEmpty()) {
			throw new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED);
		}
	}

	/**
	 * 게스트 쓰기 스위치만 본다 — 제재는 보지 않는다. 세계관처럼 남에게 보이지 않는 쓰기용이다.
	 * 스위치가 꺼져 있으면 아무것도 막지 않는다.
	 */
	public void validateNotGuest(Long memberId) {
		if (guestWriteBlocked && memberReader.getMember(memberId).isGuest()) {
			throw new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED);
		}
	}
}
