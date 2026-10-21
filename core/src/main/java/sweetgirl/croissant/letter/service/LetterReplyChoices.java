package sweetgirl.croissant.letter.service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 설문 답장에서 고른 보기 번호를 읽는다.
 *
 * <p>규칙은 하나다 — 글 안에 1~보기 개수의 숫자(또는 ①~⑨)가 있으면 그 번호를 골랐다고 본다.
 * "2", "2번입니다!", "② 요" 모두 2다. 숫자가 여러 개면 전부 고른 것으로 친다("2, 3번").
 * 다른 숫자에 붙은 숫자는 보지 않는다 — "10/3"의 1·0·3, "2026년"을 번호로 읽지 않게.
 *
 * <p>말로 쓴 답("두 번째요", 보기 문구를 그대로 쓴 답)까지 맞추려 들면 규칙이 끝없이 늘어난다.
 * 그런 답장은 번호 없음으로 남기고 어드민이 원문을 본다.
 */
final class LetterReplyChoices {

	private static final Pattern DIGIT = Pattern.compile("(?<![0-9])([1-9])(?![0-9])");
	private static final char CIRCLED_ONE = '①';

	private LetterReplyChoices() {
		// 정적 메서드만 쓴다
	}

	/** 고른 번호들, 작은 것부터. 없으면 빈 목록. */
	static List<Integer> parse(String text, int choiceCount) {
		TreeSet<Integer> picked = new TreeSet<>();
		if (text == null || choiceCount < 1) {
			return List.of();
		}
		Matcher matcher = DIGIT.matcher(text);
		while (matcher.find()) {
			add(picked, Integer.parseInt(matcher.group(1)), choiceCount);
		}
		for (char c : text.toCharArray()) {
			int circled = c - CIRCLED_ONE + 1;
			if (circled >= 1 && circled <= 9) {
				add(picked, circled, choiceCount);
			}
		}
		return new ArrayList<>(picked);
	}

	private static void add(TreeSet<Integer> picked, int choice, int choiceCount) {
		if (choice <= choiceCount) {
			picked.add(choice);
		}
	}
}
