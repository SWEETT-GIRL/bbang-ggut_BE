package sweetgirl.croissant.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.store.enums.SearchKeywordCorrectionSource;

/**
 * 검색어 교정 — "서심당"으로 들어오면 "성심당"으로 바꿔 검색한다.
 *
 * <p>가게마다 별칭을 다는 대신 검색어 하나에 한 줄을 둔다. 체인(지점 수백 곳)도 한 줄이고, 나중에 등록되는 지점도 저절로 포함된다.
 * 검색·자동완성 앞단의 {@code SearchKeywordCorrector}가 읽는다.
 */
@Entity
@Table(name = "search_keyword_correction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchKeywordCorrection extends BaseTimeEntity {

	public static final int MAX_KEYWORD_LENGTH = 100;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 검색과 같은 정규화를 거친 조회 키. 검색어 전체·단어 단위 모두 이 값으로 찾는다 */
	@Column(nullable = false, unique = true, length = MAX_KEYWORD_LENGTH)
	private String normalizedKeyword;

	@Column(nullable = false, length = MAX_KEYWORD_LENGTH)
	private String keyword;

	@Column(nullable = false)
	private String correctedQuery;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SearchKeywordCorrectionSource source;

	/** 근거가 된 실패 검색어. search_misses 행은 90일 뒤 지워져도 교정은 남는다 */
	private Long searchMissId;

	private SearchKeywordCorrection(String normalizedKeyword, String keyword, String correctedQuery,
			SearchKeywordCorrectionSource source, Long searchMissId) {
		this.normalizedKeyword = normalizedKeyword;
		this.keyword = keyword;
		this.correctedQuery = correctedQuery;
		this.source = source;
		this.searchMissId = searchMissId;
	}

	public static SearchKeywordCorrection fromSearchMiss(String normalizedKeyword, String keyword,
			String correctedQuery, Long searchMissId) {
		return new SearchKeywordCorrection(normalizedKeyword, keyword, correctedQuery,
			SearchKeywordCorrectionSource.SEARCH_MISS_AUTO, searchMissId);
	}
}
