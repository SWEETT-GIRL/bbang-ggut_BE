package sweetgirl.croissant.global.encrypt;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

	/**
	 * 엔티티 → DB: 평문을 암호화해서 저장.
	 */
	@Override
	public String convertToDatabaseColumn(String attribute) {
		if (attribute == null) {
			return null;
		}
		return EncryptorHolder.getEncryptor().encrypt(attribute);
	}

	/**
	 * DB → 엔티티: 암호문을 복호화해서 원래 값으로 복원.
	 */
	@Override
	public String convertToEntityAttribute(String dbData) {
		if (dbData == null) {
			return null;
		}
		return EncryptorHolder.getEncryptor().decrypt(dbData);
	}
}
