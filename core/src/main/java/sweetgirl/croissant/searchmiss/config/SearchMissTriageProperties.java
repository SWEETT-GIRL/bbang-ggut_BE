package sweetgirl.croissant.searchmiss.config;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;
import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;

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
	 * 사람 검수 없이 적용할 판정. 비어 있으면 shadow mode — 판정·근거만 기록하고 교정 추가·처리 상태 변경은 하지 않는다.
	 *
	 * <p>판정마다 따로 켠다. 운영 1회차(10/17) 검토에서 교정(CORRECTED)은 25건 중 24건이 맞았지만 신규 후보·보류는 사람이 봐야 했다.
	 * 여기 든 판정인데 아직 PENDING 인 행(= shadow 로 판정만 된 행)은 다음 회차에 다시 집어 실제로 적용한다.
	 */
	private Set<SearchMissVerdict> autoApplyVerdicts = EnumSet.noneOf(SearchMissVerdict.class);

	/**
	 * 하루 한 번 돌 때 꺼내는 행 수. 행마다 네이버 검색 1~3회 + LLM 최대 1회라 2,000건이면 네이버 최대 6,000건으로
	 * 하루 한도(25,000건) 안쪽이다. 첫 운영 회차(10/17)가 200건에 2분 51초 — 2,000건은 30분 안팎이다.
	 * 쌓인 미판정분(약 2,900건)을 이틀이면 소진하고, 그 뒤로는 하루 새로 쌓이는 만큼만 돈다.
	 * 발생 횟수 순으로 꺼내므로 다 못 보는 날에도 많이 찾힌 검색어부터 처리된다.
	 */
	private int batchSize = 2000;

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

	/** LLM 이 준 이름으로 우리 검색을 돌릴 때 받아 볼 가게 수. 이름이 여럿 섞였는지(브랜드인지) 가르는 데 쓴다 */
	private int nameProbeSize = 50;

	/** 처리 완료된 search_misses 행을 지우기까지의 날짜. 검색어에 개인정보가 섞여 들어와 무기한 두지 않는다 */
	private int retentionDays = 90;

	public boolean autoApplies(SearchMissVerdict verdict) {
		return autoApplyVerdicts.contains(verdict);
	}

	public boolean isShadow() {
		return autoApplyVerdicts.isEmpty();
	}
}
