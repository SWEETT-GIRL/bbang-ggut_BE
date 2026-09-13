package sweetgirl.croissant.personalization.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 양자택일의 두 극. A 가 −1 극(달콤 · 폭신 · 꽉 찬 · 고전), B 가 +1 극(담백 · 바삭 · 심플 · 신상) — TasteAxis 의 부호 약속과 같다. */
@Getter
@RequiredArgsConstructor
public enum QuizChoice {

	A(-1),
	B(1);

	private final int pole;
}
