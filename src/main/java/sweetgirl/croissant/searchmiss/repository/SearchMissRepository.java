package sweetgirl.croissant.searchmiss.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.searchmiss.entity.SearchMiss;
import sweetgirl.croissant.searchmiss.enums.SearchMissStatus;
import sweetgirl.croissant.store.service.search.SearchOutcome;

public interface SearchMissRepository extends JpaRepository<SearchMiss, Long> {

	Optional<SearchMiss> findByNormalizedKeyword(String normalizedKeyword);

	/**
	 * 이미 있는 검색어의 발생 횟수를 올린다. 갱신된 행 수를 반환하므로 0이면 신규 삽입이 필요하다는 뜻이다.
	 *
	 * <p>조회 후 분기하면 같은 검색어가 동시에 들어올 때 경합에서 깨진다. UPDATE를 먼저 시도하고
	 * 0건일 때만 INSERT하면 DB의 원자적 UPDATE에 판정을 맡길 수 있다.
	 *
	 * <p>처리 상태는 건드리지 않는다 — 관리자가 이미 판단한 검색어를 재발생만으로 목록에 되돌리면
	 * 소진이 끝나지 않는다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update SearchMiss searchMiss
			set searchMiss.occurrenceCount = searchMiss.occurrenceCount + 1,
				searchMiss.lastOccurredAt = :occurredAt,
				searchMiss.outcome = :outcome,
				searchMiss.keyword = :keyword
			where searchMiss.normalizedKeyword = :normalizedKeyword
			""")
	int incrementOccurrence(
			@Param("normalizedKeyword") String normalizedKeyword,
			@Param("keyword") String keyword,
			@Param("outcome") SearchOutcome outcome,
			@Param("occurredAt") LocalDateTime occurredAt);

	Page<SearchMiss> findByStatus(SearchMissStatus status, Pageable pageable);

	Page<SearchMiss> findByStatusAndOutcome(SearchMissStatus status, SearchOutcome outcome, Pageable pageable);

	/**
	 * 처리 시각이 기준보다 오래된 건을 삭제한다.
	 *
	 * <p>{@code PENDING}은 {@code handledAt}이 null이라 조건에 걸리지 않는다. 즉 처리 완료 건만 지워진다.
	 * 검색어에는 사람 이름·주소가 섞여 들어올 수 있어 무기한 보관하지 않는다.
	 */
	long deleteByHandledAtBefore(LocalDateTime threshold);
}
