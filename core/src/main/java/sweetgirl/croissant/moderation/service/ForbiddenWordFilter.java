package sweetgirl.croissant.moderation.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.moderation.entity.ForbiddenWord;
import sweetgirl.croissant.moderation.enums.ForbiddenWordKind;
import sweetgirl.croissant.moderation.repository.ForbiddenWordRepository;

/**
 * 금칙어 검사. 댓글 · 닉네임 · 공개 빵로그 후기가 같이 쓴다.
 *
 * <p>정규화(NFC → 소문자 → 한글 · 영문 · 숫자 외 전부 제거) 뒤 BLOCK 단어가 부분 일치하면 막는다. "시.발" 처럼 사이에
 * 기호를 끼운 우회도 같은 규칙에 걸린다. 다만 그 BLOCK 이 ALLOW 단어(예: "시발점") 안에서만 나타나면 통과시킨다.
 *
 * <p>단어는 인스턴스 메모리에 들고 있고, 어드민이 고치면 {@link #reload()} 로 다시 읽는다. 인스턴스가 둘이면 다른 쪽은
 * 다음 재시작 때 맞춰진다 — 금칙어는 분 단위로 급한 값이 아니다.
 */
@Component
@RequiredArgsConstructor
public class ForbiddenWordFilter {

	private final ForbiddenWordRepository forbiddenWordRepository;
	private final AtomicReference<Words> words = new AtomicReference<>();

	/** 막는 말이 들어 있는가. 비어 있는 문장은 괜찮다. */
	public boolean containsForbidden(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		String normalized = normalize(text);
		Words current = current();
		String stripped = normalized;
		for (String allowed : current.allow()) {
			stripped = stripped.replace(allowed, "");
		}
		for (String blocked : current.block()) {
			if (stripped.contains(blocked)) {
				return true;
			}
		}
		return false;
	}

	public void reload() {
		words.set(null);
	}

	static String normalize(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFC)
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^0-9a-z가-힣]", "");
	}

	@Transactional(readOnly = true)
	protected Words load() {
		List<ForbiddenWord> rows = forbiddenWordRepository.findAll();
		List<String> block = rows.stream()
			.filter(row -> row.getKind() == ForbiddenWordKind.BLOCK)
			.map(row -> normalize(row.getWord()))
			.filter(word -> !word.isEmpty())
			.toList();
		List<String> allow = rows.stream()
			.filter(row -> row.getKind() == ForbiddenWordKind.ALLOW)
			.map(row -> normalize(row.getWord()))
			.filter(word -> !word.isEmpty())
			.toList();
		return new Words(block, allow);
	}

	private Words current() {
		Words current = words.get();
		if (current == null) {
			current = load();
			words.compareAndSet(null, current);
		}
		return current;
	}

	record Words(List<String> block, List<String> allow) {
	}
}
