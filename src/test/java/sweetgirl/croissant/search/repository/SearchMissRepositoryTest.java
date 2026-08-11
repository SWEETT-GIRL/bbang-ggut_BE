package sweetgirl.croissant.search.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.search.entity.SearchMiss;
import sweetgirl.croissant.search.enums.SearchMissStatus;
import sweetgirl.croissant.store.service.search.SearchOutcome;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:search-miss-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class SearchMissRepositoryTest {

	private static final LocalDateTime FIRST_OCCURRED = LocalDateTime.of(2026, 8, 1, 10, 0);
	private static final LocalDateTime SECOND_OCCURRED = LocalDateTime.of(2026, 8, 5, 15, 30);

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private SearchMissRepository searchMissRepository;

	@Autowired
	private EntityManager entityManager;

	private SearchMiss persistMiss(String normalizedKeyword, String keyword, SearchOutcome outcome) {
		return searchMissRepository.saveAndFlush(
			SearchMiss.firstOccurrence(normalizedKeyword, keyword, outcome, FIRST_OCCURRED));
	}

	@Test
	@DisplayName("최초 발생은 횟수 1, PENDING 상태로 저장된다")
	void firstOccurrence_savedAsPendingWithSingleCount() {
		persistMiss("성수동크로플", "성수동 크로플", SearchOutcome.EMPTY);

		SearchMiss found = searchMissRepository.findByNormalizedKeyword("성수동크로플").orElseThrow();

		assertThat(found.getKeyword()).isEqualTo("성수동 크로플");
		assertThat(found.getOutcome()).isEqualTo(SearchOutcome.EMPTY);
		assertThat(found.getOccurrenceCount()).isEqualTo(1L);
		assertThat(found.getStatus()).isEqualTo(SearchMissStatus.PENDING);
		assertThat(found.getFirstOccurredAt()).isEqualTo(FIRST_OCCURRED);
		assertThat(found.getLastOccurredAt()).isEqualTo(FIRST_OCCURRED);
		assertThat(found.getHandledAt()).isNull();
	}

	@Test
	@DisplayName("같은 검색어가 다시 실패하면 새 행이 아니라 횟수가 올라간다")
	void incrementOccurrence_updatesExistingRow() {
		persistMiss("성수동크로플", "성수동 크로플", SearchOutcome.EMPTY);

		int updated = searchMissRepository.incrementOccurrence(
			"성수동크로플", "성수동크로플", SearchOutcome.MISMATCH, SECOND_OCCURRED);

		assertThat(updated).isEqualTo(1);
		assertThat(searchMissRepository.count()).isEqualTo(1L);

		SearchMiss found = searchMissRepository.findByNormalizedKeyword("성수동크로플").orElseThrow();
		assertThat(found.getOccurrenceCount()).isEqualTo(2L);
		assertThat(found.getLastOccurredAt()).isEqualTo(SECOND_OCCURRED);
		// 최초 발생 시각은 보존한다 — "언제부터 실패했나"가 사라지면 안 된다
		assertThat(found.getFirstOccurredAt()).isEqualTo(FIRST_OCCURRED);
		// 원문과 판정 결과는 마지막 값으로 갱신한다
		assertThat(found.getKeyword()).isEqualTo("성수동크로플");
		assertThat(found.getOutcome()).isEqualTo(SearchOutcome.MISMATCH);
	}

	@Test
	@DisplayName("없는 검색어를 갱신하면 0건이 반환되어 신규 삽입이 필요함을 알린다")
	void incrementOccurrence_returnsZeroWhenAbsent() {
		int updated = searchMissRepository.incrementOccurrence(
			"없는검색어", "없는 검색어", SearchOutcome.EMPTY, SECOND_OCCURRED);

		assertThat(updated).isZero();
		assertThat(searchMissRepository.count()).isZero();
	}

	@Test
	@DisplayName("재발생은 처리 상태를 되돌리지 않는다")
	void incrementOccurrence_doesNotResetHandledStatus() {
		SearchMiss searchMiss = persistMiss("맘모스베이커리", "맘모스 베이커리", SearchOutcome.MISMATCH_LOOSE);
		searchMiss.handle(SearchMissStatus.ALREADY_EXISTS, SECOND_OCCURRED);
		searchMissRepository.saveAndFlush(searchMiss);

		searchMissRepository.incrementOccurrence(
			"맘모스베이커리", "맘모스 베이커리", SearchOutcome.MISMATCH_LOOSE, SECOND_OCCURRED);

		SearchMiss found = searchMissRepository.findByNormalizedKeyword("맘모스베이커리").orElseThrow();
		// 이미 판단한 검색어가 재발생만으로 목록에 돌아오면 소진이 끝나지 않는다
		assertThat(found.getStatus()).isEqualTo(SearchMissStatus.ALREADY_EXISTS);
		assertThat(found.getOccurrenceCount()).isEqualTo(2L);
	}

	@Test
	@DisplayName("PENDING 목록을 발생 횟수 많은 순으로 조회한다")
	void findByStatus_ordersByOccurrenceCount() {
		persistMiss("가나다", "가나다", SearchOutcome.EMPTY);
		persistMiss("라마바", "라마바", SearchOutcome.EMPTY);
		searchMissRepository.incrementOccurrence("라마바", "라마바", SearchOutcome.EMPTY, SECOND_OCCURRED);
		searchMissRepository.incrementOccurrence("라마바", "라마바", SearchOutcome.EMPTY, SECOND_OCCURRED);

		var page = searchMissRepository.findByStatus(SearchMissStatus.PENDING,
			PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "occurrenceCount")));

		assertThat(page.getContent()).hasSize(2);
		assertThat(page.getContent().get(0).getNormalizedKeyword()).isEqualTo("라마바");
		assertThat(page.getContent().get(0).getOccurrenceCount()).isEqualTo(3L);
	}

	@Test
	@DisplayName("처리된 건은 PENDING 목록에서 빠지고 outcome으로 좁혀 조회할 수 있다")
	void findByStatusAndOutcome_filtersHandledAndOutcome() {
		persistMiss("빈결과", "빈결과", SearchOutcome.EMPTY);
		persistMiss("불일치", "불일치", SearchOutcome.MISMATCH_LOOSE);
		SearchMiss handled = persistMiss("처리됨", "처리됨", SearchOutcome.EMPTY);
		handled.handle(SearchMissStatus.STORE_ADDED, SECOND_OCCURRED);
		searchMissRepository.saveAndFlush(handled);

		var pendingEmpty = searchMissRepository.findByStatusAndOutcome(
			SearchMissStatus.PENDING, SearchOutcome.EMPTY, PageRequest.of(0, 10));

		assertThat(pendingEmpty.getContent()).hasSize(1);
		assertThat(pendingEmpty.getContent().get(0).getNormalizedKeyword()).isEqualTo("빈결과");
	}

	@Test
	@DisplayName("처리 완료 후 기준 시각이 지난 건만 삭제되고 PENDING은 남는다")
	void deleteByHandledAtBefore_keepsPending() {
		SearchMiss old = persistMiss("오래된처리", "오래된 처리", SearchOutcome.EMPTY);
		old.handle(SearchMissStatus.IGNORED, LocalDateTime.of(2026, 1, 1, 0, 0));
		SearchMiss recent = persistMiss("최근처리", "최근 처리", SearchOutcome.EMPTY);
		recent.handle(SearchMissStatus.STORE_ADDED, LocalDateTime.of(2026, 8, 10, 0, 0));
		persistMiss("미처리", "미처리", SearchOutcome.EMPTY);
		searchMissRepository.flush();

		long deleted = searchMissRepository.deleteByHandledAtBefore(LocalDateTime.of(2026, 5, 1, 0, 0));
		entityManager.flush();
		entityManager.clear();

		assertThat(deleted).isEqualTo(1L);
		assertThat(searchMissRepository.findByNormalizedKeyword("오래된처리")).isEmpty();
		// PENDING은 handledAt이 null이라 조건에 걸리지 않는다
		assertThat(searchMissRepository.findByNormalizedKeyword("미처리")).isNotEmpty();
		assertThat(searchMissRepository.findByNormalizedKeyword("최근처리")).isNotEmpty();
	}

	@Test
	@DisplayName("정규화 키가 같으면 서로 다른 원문이라도 한 행으로 묶인다")
	void normalizedKeyword_isUniqueAcrossSpacingVariants() {
		persistMiss("성수동크로플", "성수동 크로플", SearchOutcome.EMPTY);

		// "성수동크로플"로 띄어쓰기 없이 친 사용자의 검색도 같은 행을 갱신해야 한다
		int updated = searchMissRepository.incrementOccurrence(
			"성수동크로플", "성수동크로플", SearchOutcome.EMPTY, SECOND_OCCURRED);

		assertThat(updated).isEqualTo(1);
		assertThat(searchMissRepository.count()).isEqualTo(1L);
	}
}
