package sweetgirl.croissant.global.encrypt;

import org.springframework.stereotype.Component;

/**
 * JPA {@code AttributeConverter}는 Hibernate가 인스턴스화하므로 스프링 빈 주입을 받을 수 없다.
 * 이 홀더가 스프링 컨텍스트 기동 시 생성자 주입으로 {@link AesEncryptor}를 받아 static 참조로 보관하고,
 * 컨버터는 {@link #getEncryptor()}로 이를 조회한다.
 */
@Component
public class EncryptorHolder {

	private static AesEncryptor encryptor;

	public EncryptorHolder(AesEncryptor aesEncryptor) {
		EncryptorHolder.encryptor = aesEncryptor;
	}

	public static AesEncryptor getEncryptor() {
		if (encryptor == null) {
			throw new IllegalStateException("AesEncryptor가 아직 초기화되지 않았습니다.");
		}
		return encryptor;
	}
}
