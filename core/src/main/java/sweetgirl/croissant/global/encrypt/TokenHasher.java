package sweetgirl.croissant.global.encrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Component;

// SHA-256
@Component
public class TokenHasher {

	/**
	 * 입력값을 SHA-256으로 해시해서 64자리 16진수 문자열(hex)로 반환.
	 */
	public String hash(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));

			// byte[] → hex 문자열 변환 (각 바이트를 2자리 16진수로)
			StringBuilder hex = new StringBuilder();
			for (byte b : hashBytes) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}
}
