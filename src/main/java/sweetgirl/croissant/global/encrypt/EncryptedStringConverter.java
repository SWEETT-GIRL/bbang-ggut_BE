package sweetgirl.croissant.global.encrypt;

import org.springframework.beans.factory.annotation.Autowired;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

	// Hibernate가 이 컨버터를 인스턴스화할 때 Spring이 AesEncryptor를 주입
	@Autowired
	private AesEncryptor aesEncryptor;

	/**
	 * 엔티티 → DB: 평문을 암호화해서 저장.
	 */
	@Override
	public String convertToDatabaseColumn(String attribute) {
		if (attribute == null) {
			return null;
		}
		return aesEncryptor.encrypt(attribute);
	}

	/**
	 * DB → 엔티티: 암호문을 복호화해서 원래 값으로 복원.
	 */
	@Override
	public String convertToEntityAttribute(String dbData) {
		if (dbData == null) {
			return null;
		}
		return aesEncryptor.decrypt(dbData);
	}
}
