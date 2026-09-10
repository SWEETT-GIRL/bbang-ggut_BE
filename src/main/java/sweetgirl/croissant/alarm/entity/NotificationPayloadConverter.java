package sweetgirl.croissant.alarm.entity;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 타입별 부수 값을 한 칸에 담고 꺼낸다.
 *
 * <p>ObjectMapper를 주입받지 않고 직접 든다 — AttributeConverter는 Hibernate가 만들기 때문에
 * 스프링 빈이 들어오지 않는다. 여기서 다루는 것은 문자열 맵뿐이라 설정이 필요 없다.
 *
 * <p>읽다가 깨진 값을 만나면 빈 맵으로 넘어간다. payload가 상하는 것은 알림 한 줄의 부수 정보를
 * 잃는 일이고, 그것 때문에 알림함 전체가 500으로 떨어지는 것이 더 나쁘다.
 */
@Converter
public class NotificationPayloadConverter implements AttributeConverter<Map<String, String>, String> {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	// 익명 TypeReference 대신 JavaType 을 만들어 둔다 — 포맷터가 빈 중괄호로 접으면
	// 코드 스타일 검사에 걸리고, 어차피 한 번만 만들면 되는 값이다.
	private static final JavaType TYPE = MAPPER.getTypeFactory()
		.constructMapType(HashMap.class, String.class, String.class);

	@Override
	public String convertToDatabaseColumn(Map<String, String> attribute) {
		if (attribute == null || attribute.isEmpty()) {
			return null;
		}
		try {
			return MAPPER.writeValueAsString(attribute);
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public Map<String, String> convertToEntityAttribute(String dbData) {
		if (dbData == null || dbData.isBlank()) {
			return Map.of();
		}
		try {
			return MAPPER.readValue(dbData, TYPE);
		} catch (Exception e) {
			return Map.of();
		}
	}
}
