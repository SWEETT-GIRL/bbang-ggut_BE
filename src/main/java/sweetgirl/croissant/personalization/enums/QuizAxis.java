package sweetgirl.croissant.personalization.enums;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.exception.PersonalizationException;

/** 검사 문항이 묻는 축. 요청 JSON 은 소문자(taste · texture · filling · vibe)로 온다 — FE BbtiAxis 와 같은 글자. */
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
			.orElseThrow(() -> new PersonalizationException(PersonalizationErrorCode.QUIZ_ANSWERS_INVALID));
	}
}
