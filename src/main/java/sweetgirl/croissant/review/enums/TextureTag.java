package sweetgirl.croissant.review.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TextureTag {
	CRISPY("crispy"),
	FLAKY("flaky"),
	MOIST("moist"),
	CHEWY("chewy"),
	FUDGY("fudgy"),
	FLUFFY("fluffy"),
	SOFT("soft"),
	DENSE("dense"),
	NUTTY("nutty"),
	BUTTERY("buttery"),
	SALTY("salty"),
	PLAIN("plain"),
	RICH("rich"),
	LIGHT("light"),
	GREASY("greasy");

	private final String value;

	@JsonCreator
	public static TextureTag from(String value) {
		for (TextureTag tag : values()) {
			if (tag.value.equalsIgnoreCase(value)) {
				return tag;
			}
		}
		throw new IllegalArgumentException("Invalid texture tag: " + value);
	}

	@JsonValue
	public String getValue() {
		return value;
	}
}
