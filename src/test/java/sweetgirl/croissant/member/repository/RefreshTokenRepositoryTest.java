package sweetgirl.croissant.member.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.RefreshToken;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:refresh-token-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class RefreshTokenRepositoryTest {

	private static final String DEVICE_ID = "device-001";

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private MemberRepository memberRepository;

	// 로그인이 겹치면 다른 트랜잭션이 같은 행을 먼저 지운다. 그때 예외가 나면 로그인 자체가 500이 됐다.
	@Test
	@DisplayName("지울 토큰이 없어도 예외 없이 지나간다")
	void deleteByMemberIdAndDeviceId_doesNotThrow_whenNothingMatches() {
		Member member = saveMember();

		assertThatCode(() -> refreshTokenRepository.deleteByMemberIdAndDeviceId(member.getId(), DEVICE_ID))
			.doesNotThrowAnyException();
	}

	// 레이스로 같은 기기 토큰이 두 개 남더라도 다음 로그인 때 한 번에 정리된다.
	@Test
	@DisplayName("같은 기기의 토큰이 여러 개여도 모두 지운다")
	void deleteByMemberIdAndDeviceId_removesEveryTokenOfTheDevice() {
		Member member = saveMember();
		saveToken(member, DEVICE_ID, "hash-1");
		saveToken(member, DEVICE_ID, "hash-2");
		saveToken(member, "other-device", "hash-3");

		refreshTokenRepository.deleteByMemberIdAndDeviceId(member.getId(), DEVICE_ID);

		assertThat(refreshTokenRepository.findAllByMember(member))
			.extracting(RefreshToken::getDeviceId)
			.containsExactly("other-device");
	}

	private Member saveMember() {
		Member member = Member.builder().nickname("게스트").build();
		member.markAsGuest();
		return memberRepository.save(member);
	}

	private void saveToken(Member member, String deviceId, String token) {
		refreshTokenRepository.save(RefreshToken.builder()
			.member(member)
			.deviceId(deviceId)
			.token(token)
			.expiredAt(LocalDateTime.now().plusDays(14))
			.build());
	}
}
