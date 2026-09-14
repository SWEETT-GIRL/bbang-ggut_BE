package sweetgirl.croissant.personalization.enums;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 검사 문항이 묻는 축. 요청 JSON 은 소문자(taste · texture · filling · vibe)로 온다 — FE BbtiAxis 와 같은 글자.
 * 모르는 글자는 Jackson 이 본문 파싱 오류로 감싸 공통 400 이 된다 — 여기서 도메인 예외를 던져도 그 코드는 나가지 않으므로 단순하게 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum QuizAxis {

	TASTE("taste"),
	TEXTURE("texture"),
	FILLING("filling"),
	VIBE("vibe");

	@JsonValue
	private final String key;

	@JsonCreator
	public static QuizAxis fromKey(String key) {
		return Arrays.stream(values())
			.filter(axis -> axis.key.equals(key))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("알 수 없는 축: " + key));
	}
}
