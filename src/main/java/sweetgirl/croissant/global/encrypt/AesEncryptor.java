package sweetgirl.croissant.global.encrypt;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AesEncryptor {

	// GCM 표준 IV 길이: 12바이트 권장 (NIST SP 800-38D)
	private static final int GCM_IV_LENGTH = 12;

	// GCM 인증 태그 길이: 128비트 (최대 강도)
	private static final int GCM_TAG_LENGTH = 128;

	// application-{profile}.properties 의 encrypt.secret-key 값 (Base64 인코딩된 32바이트 AES-256 키)
	private final SecretKeySpec secretKey;

	public AesEncryptor(@Value("${encrypt.secret-key}") String base64Key) {
		byte[] keyBytes = Base64.getDecoder().decode(base64Key);
		this.secretKey = new SecretKeySpec(keyBytes, "AES");
	}

	public String encrypt(String plainText) {
		try {
			// 매 암호화마다 랜덤 IV 생성 — 동일 값도 매번 다른 결과를 보장
			byte[] iv = new byte[GCM_IV_LENGTH];
			new SecureRandom().nextBytes(iv);

			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

			// 암호화 — doFinal 결과에 GCM Tag(16바이트)가 자동으로 붙음
			byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

			// 복호화 시 IV가 필요하므로 암호문 앞에 IV를 붙여서 함께 저장
			byte[] combined = new byte[GCM_IV_LENGTH + encrypted.length];
			System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
			System.arraycopy(encrypted, 0, combined, GCM_IV_LENGTH, encrypted.length);

			return Base64.getEncoder().encodeToString(combined);
		} catch (Exception e) {
			throw new RuntimeException("Encryption failed", e);
		}
	}

	public String decrypt(String cipherText) {
		try {
			byte[] combined = Base64.getDecoder().decode(cipherText);

			// 저장 시 앞에 붙인 IV(12바이트) 분리
			byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
			// 나머지가 실제 암호문 + GCM Tag
			byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

			// GCM Tag 검증 실패 시 AEADBadTagException 발생 → 변조 감지
			return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new RuntimeException("Decryption failed", e);
		}
	}
}
