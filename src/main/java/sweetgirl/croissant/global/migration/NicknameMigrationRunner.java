package sweetgirl.croissant.global.migration;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.global.encrypt.AesEncryptor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

/**
 * 암호화된 닉네임(AES-256-GCM)을 형용사+명사 랜덤 닉네임으로 일괄 변환.
 * 복호화에 실패하면 이미 평문 닉네임이므로 건너뜀 (멱등성 보장).
 * 마이그레이션 완료 후 이 클래스는 삭제해도 됩니다.
 */
@Slf4j
@Component
@Profile({"staging", "prod"})
@RequiredArgsConstructor
public class NicknameMigrationRunner implements ApplicationRunner {

	private final MemberRepository memberRepository;
	private final NicknameGenerator nicknameGenerator;
	private final AesEncryptor aesEncryptor;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		log.info("[NicknameMigration] 닉네임 마이그레이션 시작");

		List<Member> members = memberRepository.findAll();
		int count = 0;

		for (Member member : members) {
			if (!isEncrypted(member.getNickname())) {
				continue;
			}
			member.resetNickname(nicknameGenerator.generate());
			count++;
		}

		log.info("[NicknameMigration] 완료 — {}명 닉네임 변경", count);
	}

	private boolean isEncrypted(String nickname) {
		if (nickname == null) {
			return false;
		}
		try {
			aesEncryptor.decrypt(nickname);
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
