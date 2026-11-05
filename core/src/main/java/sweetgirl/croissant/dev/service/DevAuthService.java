package sweetgirl.croissant.dev.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.service.NotificationSettingProvider;
import sweetgirl.croissant.dev.dto.response.DevTestLoginResponse;
import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.member.util.NicknameNormalizer;

/**
 * 카카오 없이 쓰는 테스트 회원 로그인. <b>local·staging 에서만 뜬다 — prod 에는 올라가지 않는다.</b>
 *
 * <p>커뮤니티(팔로우 · 댓글 · 톡 …)는 로그인한 회원 둘 이상이 있어야 돌려볼 수 있는데, dev 에서 카카오 계정을 둘씩
 * 쓰기는 번거롭다. 닉네임으로 USER 회원을 찾거나 만들고 평소와 같은 JWT 를 준다.
 */
@Profile({"local", "staging"})
@Service
@RequiredArgsConstructor
public class DevAuthService {

	private final MemberRepository memberRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final NotificationSettingProvider notificationSettingProvider;
	private final JwtProvider jwtProvider;
	private final TokenHasher tokenHasher;

	@Value("${jwt.refresh-token-expiry}")
	private long refreshTokenExpiry;

	@Transactional
	public DevTestLoginResponse login(String nickname, String deviceId) {
		LocalDateTime now = LocalDateTime.now();
		// 회원 유니크는 정규화 닉네임(uq_member_nickname_normalized_alive)이라 같은 기준으로 찾는다 — 아니면 save 가 유니크 충돌
		Optional<Member> existing = memberRepository.findByNicknameNormalizedAndDeletedAtIsNull(
			NicknameNormalizer.normalize(nickname));
		Member member = existing.orElseGet(() -> memberRepository.save(Member.registering(nickname, now)));
		Long memberId = member.getId();
		notificationSettingProvider.getOrCreate(memberId);
		String accessToken = jwtProvider.generateAccessToken(memberId);
		String refreshToken = jwtProvider.generateRefreshToken(memberId);
		String device = deviceId == null || deviceId.isBlank() ? "dev-test-" + nickname : deviceId;
		refreshTokenRepository.deleteByMemberIdAndDeviceId(memberId, device);
		refreshTokenRepository.save(RefreshToken.builder()
			.member(member)
			.deviceId(device)
			.token(tokenHasher.hash(refreshToken))
			.expiredAt(now.plusSeconds(refreshTokenExpiry / 1000))
			.build());
		return new DevTestLoginResponse(accessToken, refreshToken, memberId, member.getNickname(), existing.isEmpty());
	}
}
