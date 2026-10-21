package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

	/** 글이 아닌 상호작용(팔로우 · 좋아요)까지 막는 제재. 작성 제한은 글만 막으므로 여기 없다. */
	private static final List<MemberRestrictionType> INTERACTION_BLOCKING_TYPES = List.of(
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
		validateNotRestricted(memberId);
	}

	/** 준비된 비공개 튜토리얼만 게스트 여부와 무관하게 허용한다. 제재 검증은 유지한다. */
	public void validateNotRestricted(Long memberId) {
		if (memberId == null) {
			throw new MemberException(MemberErrorCode.UNAUTHORIZED);
		}
		if (isWriteRestricted(memberId)) {
			throw new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED);
		}
	}

	/**
	 * 팔로우 · 좋아요처럼 글을 남기지 않는 상호작용을 할 수 있는가 — 정지(SUSPENDED) · 강퇴(BANNED)만 막는다.
	 *
	 * <p>정지는 "로그인은 되지만 읽기 전용"이다. 작성 제한(WRITE_RESTRICTED)은 남에게 보이는 글(댓글 · 공개 전환 · 태그 · 톡)만
	 * 못 쓰고 팔로우 · 좋아요는 된다. 게스트 여부는 부르는 쪽이 {@link MemberReader#requireLoggedIn} 으로 먼저 본다.
	 */
	public void validateCanInteract(Long memberId) {
		if (memberId == null) {
			throw new MemberException(MemberErrorCode.UNAUTHORIZED);
		}
		boolean suspended = !memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
			memberId, INTERACTION_BLOCKING_TYPES, LocalDateTime.now()).isEmpty();
		if (suspended) {
			throw new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED);
		}
	}

	/**
	 * 정지(SUSPENDED) 중이면 풀리는 때. 무기한이면 {@code Optional.of(null)} 이 아니라 {@link Suspension#indefinite()} 다.
	 * 정지가 아니면 비어 있다.
	 */
	public Optional<Suspension> currentSuspension(Long memberId) {
		return memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
			memberId, List.of(MemberRestrictionType.SUSPENDED), LocalDateTime.now()).stream()
			.findFirst()
			.map(restriction -> new Suspension(restriction.getEndsAt()));
	}

	/** 정지 상태. {@code until} 이 null 이면 무기한. */
	public record Suspension(LocalDateTime until) {
		public boolean indefinite() {
			return until == null;
		}
	}

	/** 프로필 제한(PROFILE_RESTRICTED) 중인가 — 남에게 프로필이 안 보이고 검색 · 추천에서 빠지고 닉네임을 못 바꾼다. */
	public boolean isProfileRestricted(Long memberId) {
		return !memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
			memberId, List.of(MemberRestrictionType.PROFILE_RESTRICTED), LocalDateTime.now()).isEmpty();
	}

	public void validateProfileNotRestricted(Long memberId) {
		if (memberId == null) {
			throw new MemberException(MemberErrorCode.UNAUTHORIZED);
		}
		if (isProfileRestricted(memberId)) {
			throw new MemberException(MemberErrorCode.MEMBER_PROFILE_RESTRICTED);
		}
	}

	/**
	 * 지금 글쓰기가 막힌 제재 중인가. 던지지 않고 답만 한다.
	 *
	 * <p>리워드 적립이 이걸 본다 — 제재 중이면 요청을 실패시키지 않고 부스러기만 주지 않는다.
	 */
	public boolean isWriteRestricted(Long memberId) {
		return !memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
			memberId,
			WRITE_BLOCKING_TYPES,
			LocalDateTime.now()).isEmpty();
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
