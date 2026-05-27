package sweetgirl.croissant.global.migration;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import sweetgirl.croissant.global.encrypt.AesEncryptor;
import sweetgirl.croissant.global.encrypt.TokenHasher;

/**
 * 개인정보 암호화 도입 이전에 평문으로 저장된 기존 데이터를 일괄 변환하는 일회성 마이그레이션.
 * - member.nickname    : AES-256-GCM 암호화
 * - oauth.email        : AES-256-GCM 암호화
 * - oauth.provider_id  : SHA-256 해시
 * - refresh_token.token: SHA-256 해시
 *
 * 이미 변환된 데이터는 건너뜀 (멱등성 보장).
 * 마이그레이션 완료 후 이 클래스는 삭제해도 됩니다.
 */
@Component
@Profile({"staging", "prod"})
public class PersonalDataMigrationRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PersonalDataMigrationRunner.class);

	private final JdbcTemplate jdbcTemplate;
	private final AesEncryptor aesEncryptor;
	private final TokenHasher tokenHasher;

	public PersonalDataMigrationRunner(JdbcTemplate jdbcTemplate, AesEncryptor aesEncryptor,
			TokenHasher tokenHasher) {
		this.jdbcTemplate = jdbcTemplate;
		this.aesEncryptor = aesEncryptor;
		this.tokenHasher = tokenHasher;
	}

	@Override
	public void run(ApplicationArguments args) {
		log.info("[Migration] 개인정보 암호화 마이그레이션 시작");
		migrateMemberNicknames();
		migrateOAuthData();
		migrateRefreshTokens();
		log.info("[Migration] 개인정보 암호화 마이그레이션 완료");
	}

	private void migrateMemberNicknames() {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
			"SELECT id, nickname FROM member");

		int count = 0;
		for (Map<String, Object> row : rows) {
			Long id = ((Number)row.get("id")).longValue();
			String nickname = (String)row.get("nickname");

			if (nickname == null || isAlreadyEncrypted(nickname)) {
				continue;
			}

			jdbcTemplate.update(
				"UPDATE member SET nickname = ? WHERE id = ?",
				aesEncryptor.encrypt(nickname), id);
			count++;
		}
		log.info("[Migration] member.nickname {}건 변환 완료", count);
	}

	private void migrateOAuthData() {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
			"SELECT id, provider_id, email FROM oauth");

		int count = 0;
		for (Map<String, Object> row : rows) {
			Long id = ((Number)row.get("id")).longValue();
			String providerId = (String)row.get("provider_id");
			String email = (String)row.get("email");

			String newProviderId = (providerId != null && !isSha256Hash(providerId))
				? tokenHasher.hash(providerId) : providerId;

			String newEmail = (email != null && !isAlreadyEncrypted(email))
				? aesEncryptor.encrypt(email) : email;

			jdbcTemplate.update(
				"UPDATE oauth SET provider_id = ?, email = ? WHERE id = ?",
				newProviderId, newEmail, id);
			count++;
		}
		log.info("[Migration] oauth {}건 변환 완료", count);
	}

	private void migrateRefreshTokens() {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
			"SELECT id, token FROM refresh_token WHERE revoked_at IS NULL AND expired_at > NOW()");

		int count = 0;
		for (Map<String, Object> row : rows) {
			Long id = ((Number)row.get("id")).longValue();
			String token = (String)row.get("token");

			if (token == null || isSha256Hash(token)) {
				continue;
			}

			jdbcTemplate.update(
				"UPDATE refresh_token SET token = ? WHERE id = ?",
				tokenHasher.hash(token), id);
			count++;
		}
		log.info("[Migration] refresh_token {}건 변환 완료", count);
	}

	/**
	 * AES-256-GCM 암호화 여부 확인.
	 * 복호화 성공 시 이미 암호화된 값으로 판단.
	 */
	private boolean isAlreadyEncrypted(String value) {
		try {
			aesEncryptor.decrypt(value);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * SHA-256 해시 여부 확인.
	 * SHA-256 결과는 항상 소문자 16진수 64자리.
	 */
	private boolean isSha256Hash(String value) {
		return value != null && value.matches("[a-f0-9]{64}");
	}
}
