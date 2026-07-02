package sweetgirl.croissant.global.encrypt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EncryptedStringConverterTest {

	// 테스트용 Base64 인코딩된 32바이트(AES-256) 키
	private static final String TEST_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

	private EncryptedStringConverter converter;

	@BeforeEach
	void setUp() {
		// 홀더에 실제 암호화기를 주입해 컨버터가 static 조회로 사용하도록 준비
		new EncryptorHolder(new AesEncryptor(TEST_KEY));
		converter = new EncryptedStringConverter();
	}

	@Test
	@DisplayName("평문을 암호화 후 복호화하면 원래 값으로 복원된다")
	void encryptThenDecrypt_returnsOriginal() {
		String plainText = "010-1234-5678";

		String encrypted = converter.convertToDatabaseColumn(plainText);
		String decrypted = converter.convertToEntityAttribute(encrypted);

		assertThat(encrypted).isNotEqualTo(plainText);
		assertThat(decrypted).isEqualTo(plainText);
	}

	@Test
	@DisplayName("null은 암호화·복호화 모두 null로 통과한다")
	void nullPassesThrough() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}

	@Test
	@DisplayName("동일 평문도 매번 다른 암호문을 생성한다 (랜덤 IV)")
	void sameInput_producesDifferentCipher() {
		String plainText = "same-value";

		String first = converter.convertToDatabaseColumn(plainText);
		String second = converter.convertToDatabaseColumn(plainText);

		assertThat(first).isNotEqualTo(second);
		assertThat(converter.convertToEntityAttribute(first)).isEqualTo(plainText);
		assertThat(converter.convertToEntityAttribute(second)).isEqualTo(plainText);
	}
}
