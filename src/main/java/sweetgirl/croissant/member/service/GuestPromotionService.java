package sweetgirl.croissant.member.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

/**
 * 소셜 최초 가입 시 어떤 Member에 OAuth를 붙일지 결정한다.
 *
 * 같은 기기(deviceId)에서 게스트로 쓰던 계정이 있으면 새 Member를 만들지 않고
 * 그 계정을 회원으로 승격한다. memberId가 그대로라 게스트로 만든 코스·찜이
 * 로그인 후에도 계속 보인다. 게스트 이력이 없으면(= 게스트 세션을 쓰지 않는 앱)
 * 기존과 동일하게 새 Member를 만든다.
 */
@Service
@RequiredArgsConstructor
public class GuestPromotionService {

	private final RefreshTokenRepository refreshTokenRepository;
	private final MemberRepository memberRepository;
	private final NicknameGenerator nicknameGenerator;
	private final GuestDataMigrationService guestDataMigrationService;

	@Transactional
	public Member resolveMemberForNewOAuth(String deviceId, Long callerGuestId) {
		return resolveCallerGuest(callerGuestId)
			.or(() -> findReusableGuest(deviceId))
			.map(guest -> {
				guest.promoteToUser();
				return guest;
			})
			.orElseGet(() -> memberRepository.save(
				Member.builder().nickname(nicknameGenerator.generate()).build()));
	}

	/**
	 * 승격이 불가능한 로그인(이미 이 소셜로 가입한 계정 / 탈퇴 후 재가입으로 새로 만든 계정)에서,
	 * 게스트가 쌓아둔 데이터를 로그인한 회원에게 옮긴다.
	 * 승격으로 이미 같은 Member가 된 경우엔 게스트 조회 자체가 비므로 아무 일도 하지 않는다.
	 */
	@Transactional
	public void mergeGuestDataInto(Member target, String deviceId, Long callerGuestId) {
		resolveCallerGuest(callerGuestId)
			.or(() -> findReusableGuest(deviceId))
			.filter(guest -> !guest.getId().equals(target.getId()))
			.ifPresent(guest -> guestDataMigrationService.migrate(guest.getId(), target.getId()));
	}

	/**
	 * 로그인 요청의 Authorization에 실려 온 게스트 토큰의 주인.
	 *
	 * deviceId 매칭은 재설치·키체인 초기화로 값이 바뀌면 영영 못 찾는다 —
	 * 지금 이 요청을 보낸 게스트가 누구인지는 토큰이 직접 말하므로 이쪽을 먼저 믿는다.
	 */
	private Optional<Member> resolveCallerGuest(Long callerGuestId) {
		if (callerGuestId == null) {
			return Optional.empty();
		}
		return memberRepository.findById(callerGuestId)
			.filter(member -> "GUEST".equals(member.getMemberType()))
			.filter(member -> !member.isDeleted());
	}

	// memberType='GUEST' + 미탈퇴 조건은 findGuestTokensByDeviceId 쿼리가 이미 걸고 있다.
	private Optional<Member> findReusableGuest(String deviceId) {
		if (deviceId == null || deviceId.isBlank()) {
			return Optional.empty();
		}
		return refreshTokenRepository.findGuestTokensByDeviceId(deviceId).stream()
			.map(RefreshToken::getMember)
			.findFirst();
	}
}
