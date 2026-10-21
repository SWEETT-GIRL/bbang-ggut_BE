package sweetgirl.croissant.breadlog.service;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 빵탑에 쌓인 빵을 정한다. 사용자가 고른 값이 있으면 그것이고, 없으면 기록 id 로 하나 고른다.
 *
 * <p>개편 전에 쌓인 기록은 빵탑을 몰라서 이 칸이 비어 있다. 그렇다고 매번 새로 뽑으면 목록을
 * 스크롤할 때마다 같은 기록의 빵이 바뀌므로, <b>기록 id 로 고정</b>한다 — 같은 기록은 늘 같은 빵이다.
 *
 * <p>고른 값을 DB 에 박지 않는 이유: 빵 종류가 늘거나 고르는 규칙이 바뀌면 과거 기록에도 바로
 * 반영되어야 한다. DB 의 빈 칸은 "사용자가 아직 고르지 않았다"는 사실 그대로 남는다.
 *
 * <p>목록과 순서는 앱의 {@code BREAD_KINDS} 와 같아야 한다. 앱도 같은 규칙으로 고르므로,
 * 서버가 내려주기 전과 후의 그림이 어긋나지 않는다.
 */
@Component
public class TowerBreadPicker {

	private static final List<String> BREAD_KINDS = List.of("croissant", "melonpan", "soboro", "bagel", "anpan");

	public String resolve(Long recordId, String chosen) {
		if (chosen != null && !chosen.isBlank()) {
			return chosen;
		}
		if (recordId == null) {
			return null;
		}
		return BREAD_KINDS.get((int)(Math.abs(recordId) % BREAD_KINDS.size()));
	}
}
