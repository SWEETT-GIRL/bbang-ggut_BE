package sweetgirl.croissant.searchmiss.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.searchmiss.enums.SearchMissStatus;
import sweetgirl.croissant.store.service.search.SearchOutcome;

/**
 * 결과를 못 낸 검색어. 관리자가 보고 가게를 추가하기 위한 작업 목록이다.
 *
 * <p>지표({@code search_query_total})는 "얼마나 자주 실패했나"에 답하지만 검색어를 담을 수 없다 —
 * 검색어는 카디널리티가 무한해 Prometheus 태그로 쓸 수 없기 때문이다. 이 엔티티가 "무슨 검색어였나"를 맡는다.
 *
 * <p>같은 검색어는 {@code normalizedKeyword} 기준으로 한 행에 합치고 {@code occurrenceCount}만 올린다.
 * 실패마다 한 행씩 쌓으면 목록이 수천 줄이 되어 사람이 소진할 수 없다.
 */
@Entity
@Table(name = "search_misses", indexes = {
	@Index(name = "idx_search_misses_status_count", columnList = "status, occurrence_count DESC"),
	@Index(name = "idx_search_misses_status_last_occurred", columnList = "status, last_occurred_at DESC")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchMiss extends BaseTimeEntity {

	/** 검색어 저장 상한. 봇이 긴 랜덤 문자열을 넣는 경우를 잘라낸다. */
	public static final int MAX_KEYWORD_LENGTH = 100;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 공백·특수문자를 제거한 중복 제거 키. "성수동 크로플"과 "성수동크로플"을 한 행으로 묶는다. */
	@Column(nullable = false, unique = true, length = MAX_KEYWORD_LENGTH)
	private String normalizedKeyword;

	/** 마지막으로 들어온 사용자 입력 원문. 관리자 화면에 보여줄 값이다. */
	@Column(nullable = false, length = MAX_KEYWORD_LENGTH)
	private String keyword;

	/**
	 * 마지막 발생 시 판정 결과.
	 *
	 * <p>{@code EMPTY}는 "진짜 없는 가게" 후보고, {@code MISMATCH}·{@code MISMATCH_LOOSE}는
	 * "있는데 검색이 못 찾은 것"일 가능성이 높다. 이 값으로 걸러내지 않으면 이미 있는 가게를
	 * 중복 등록하게 된다.
	 *
	 * <p>같은 검색어라도 발생마다 달라질 수 있어 마지막 값으로 갱신한다. 최빈값 집계는
	 * 복잡도 대비 이득이 없다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SearchOutcome outcome;

	@Column(nullable = false)
	private long occurrenceCount;

	@Column(nullable = false)
	private LocalDateTime firstOccurredAt;

	@Column(nullable = false)
	private LocalDateTime lastOccurredAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SearchMissStatus status;

	/**
	 * 처리 시각. 90일 경과 후 삭제 대상을 고르는 기준이다.
	 *
	 * <p>처리한 관리자는 기록하지 않는다 — 관리자 API에 인증이 없어(SecurityConfig에서 permitAll)
	 * 요청이 스스로 신고한 식별자는 감사 기록이 될 수 없다. 관리자 인증이 붙으면 그때 추가한다.
	 */
	private LocalDateTime handledAt;

	private SearchMiss(String normalizedKeyword, String keyword, SearchOutcome outcome, LocalDateTime occurredAt) {
		this.normalizedKeyword = normalizedKeyword;
		this.keyword = keyword;
		this.outcome = outcome;
		this.occurrenceCount = 1L;
		this.firstOccurredAt = occurredAt;
		this.lastOccurredAt = occurredAt;
		this.status = SearchMissStatus.PENDING;
	}

	public static SearchMiss firstOccurrence(String normalizedKeyword, String keyword, SearchOutcome outcome,
			LocalDateTime occurredAt) {
		return new SearchMiss(normalizedKeyword, keyword, outcome, occurredAt);
	}

	/**
	 * 같은 검색어가 다시 실패했을 때 호출한다.
	 *
	 * <p>처리 상태는 건드리지 않는다. 관리자가 이미 판단한 검색어를 재발생만으로 목록에 되돌리면
	 * 소진이 끝나지 않기 때문이다.
	 */
	public void recordOccurrence(String keyword, SearchOutcome outcome, LocalDateTime occurredAt) {
		this.keyword = keyword;
		this.outcome = outcome;
		this.occurrenceCount++;
		this.lastOccurredAt = occurredAt;
	}

	public void handle(SearchMissStatus status, LocalDateTime handledAt) {
		this.status = status;
		this.handledAt = status == SearchMissStatus.PENDING ? null : handledAt;
	}
}
