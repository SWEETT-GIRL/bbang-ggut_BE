package sweetgirl.croissant.searchmiss.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * 실패 검색어 자동 판정 설정. 임계값은 초기값이다 — 사람이 처리한 ALREADY_EXISTS·STORE_ADDED 행을 정답셋으로 맞춰 조정한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "search-miss.triage")
public class SearchMissTriageProperties {

	/** 스케줄러를 돌릴지. 외부 API·LLM 비용이 드니 기본은 꺼 둔다 */
	private boolean enabled = false;

	/**
	 * false 면 shadow mode — 판정·근거만 기록하고 별칭 INSERT·처리 상태 변경은 하지 않는다.
	 *
	 * <p>판정을 사람이 1~2주 확인한 뒤 켠다. 켤 때 이미 shadow 로 판정된 행은 {@code ai_triaged_at} 이 찍혀 있어
	 * 다시 집히지 않으므로, ALIAS_RESOLVED 등을 적용하려면 그 행의 {@code ai_triaged_at} 을 비운다.
	 */
	private boolean autoApply = false;

	/**
	 * 하루 한 번 돌 때 꺼내는 행 수. 행마다 네이버 검색 1~3회 + LLM 최대 1회라 200건이면 네이버 하루 한도(25,000건)의
	 * 수 % 안쪽이다. 발생 횟수 순으로 꺼내므로 다 못 보는 날에도 많이 찾힌 검색어부터 처리된다.
	 */
	private int batchSize = 200;

	/**
	 * {@code 벤더:모델}. 키는 벤더 공용 설정(Anthropic 은 {@code CLAUDE_API_KEY})을 쓴다.
	 * Gemini 무료 등급 하루 한도는 메뉴 분류·이유 문장과 나눠 쓰므로 Anthropic 을 기본으로 둔다.
	 */
	private String model = "ANTHROPIC:claude-haiku-4-5-20251001";

	private int maxOutputTokens = 300;

	/** 네이버 장소와 우리 가게를 같은 곳으로 볼 반경. 네이버·카카오 좌표가 수십 m 어긋나는 경우가 있다 */
	private double nearbyRadiusMeters = 50;

	/**
	 * 이 반경 안에 이름이 다른 가게가 있으면 신규 후보가 아니라 사람이 본다 — 가게가 바뀌었거나 이름을 바꿨을 수 있다.
	 * {@link #nearbyRadiusMeters}로 잡으면 성수처럼 가게가 붙어 있는 곳에서 신규 가게가 전부 검수로 빠진다.
	 */
	private double conflictRadiusMeters = 10;

	/** LLM 에 줄 블로그 글 수 */
	private int blogDisplay = 10;

	/** LLM 이 말한 정식 이름이 글자 그대로 들어 있어야 하는 근거 글 최소 수 */
	private int minEvidencePosts = 2;

	/** 별칭을 한 번에 넣을 수 있는 가게(체인 지점) 상한. 넘으면 사람이 본다 */
	private int maxAliasStores = 30;
}
