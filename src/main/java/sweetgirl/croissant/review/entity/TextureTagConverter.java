package sweetgirl.croissant.review.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import sweetgirl.croissant.review.enums.TextureTag;

@Converter
public class TextureTagConverter implements AttributeConverter<TextureTag, String> {

	@Override
	public String convertToDatabaseColumn(TextureTag attribute) {
		if (attribute == null) {
			return null;
		}
		return attribute.getValue();
	}

	@Override
	public TextureTag convertToEntityAttribute(String dbData) {
		if (dbData == null) {
			return null;
		}
		return TextureTag.from(dbData);
	}
}
