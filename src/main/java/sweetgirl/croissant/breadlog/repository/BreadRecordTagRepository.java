package sweetgirl.croissant.breadlog.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.menu.enums.MenuKind;

public interface BreadRecordTagRepository extends JpaRepository<BreadRecordTag, Long> {

	/**
	 * 메뉴·메뉴 분류까지 한 번에 당겨 온다 — {@code resolvedCategory()} 가 태그마다 메뉴를 건드리므로
	 * FETCH 를 빼면 태그 수만큼 쿼리가 나간다. 기록이 몇 건이든 이 쿼리 하나로 끝나야 한다.
	 */
	@Query("""
			SELECT tag
			FROM BreadRecordTag tag
			JOIN FETCH tag.photo photo
			LEFT JOIN FETCH tag.menu menu
			LEFT JOIN FETCH menu.menuCategory
			WHERE tag.record.id IN :recordIds
			ORDER BY tag.record.id, tag.id
			""")
	List<BreadRecordTag> findByRecordIdInWithPhoto(@Param("recordIds") List<Long> recordIds);

	void deleteAllByRecordId(Long recordId);

	/** 기록 하나의 태그를 평가 칩까지 — 수정으로 태그를 다시 만들기 전에 평가를 이름별로 옮겨 두려고. */
	@Query("""
			SELECT DISTINCT tag
			FROM BreadRecordTag tag
			LEFT JOIN FETCH tag.chips
			WHERE tag.record.id = :recordId
			""")
	List<BreadRecordTag> findAllWithChipsByRecordId(@Param("recordId") Long recordId);

	/**
	 * 그 회원이 그 가게들에서 그 빵 종류로 단 태그 — 최신 기록부터. 룰이 빵·기타로 물러선 태그는 메뉴의 분류로 잇는다
	 * ({@code resolvedCategory()} 와 같은 규칙). 내 빵 순위 줄의 평가 읽기·고치기용 — 가게 수와 무관하게 쿼리 한 번.
	 */
	@Query("""
			SELECT tag
			FROM BreadRecordTag tag
			JOIN FETCH tag.record record
			JOIN FETCH record.store store
			LEFT JOIN FETCH tag.menu menu
			LEFT JOIN FETCH menu.menuCategory menuCategory
			WHERE record.member.id = :memberId AND record.deletedAt IS NULL AND store.id IN :storeIds
				AND (tag.breadCategory = :kindName
					OR (tag.breadCategory IN ('빵', '기타') AND menuCategory.name = :kind))
			ORDER BY record.eatenAt DESC, tag.id DESC
			""")
	List<BreadRecordTag> findLatestTagsByStores(
			@Param("memberId") Long memberId,
			@Param("storeIds") List<Long> storeIds,
			@Param("kindName") String kindName,
			@Param("kind") MenuKind kind);

	@Query("""
			SELECT COUNT(tag.id)
			FROM BreadRecordTag tag
			WHERE tag.record.member.id = :memberId
			AND tag.record.deletedAt IS NULL
			AND tag.record.eatenAt >= :from
			AND tag.record.eatenAt < :to
			""")
	long countBreads(
			@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);

	/**
	 * 집계용 태그 — 분류를 태그가 아니라 메뉴에서 읽을 수 있어야 하므로 문자열이 아니라 엔티티를 준다.
	 * 폴백('빵'·'기타') 제외는 {@code resolvedCategory()} 로 메뉴까지 본 뒤라야 판단할 수 있다.
	 * 한 회원의 한 기간이라 건수가 작다.
	 */
	@Query("""
			SELECT tag
			FROM BreadRecordTag tag
			LEFT JOIN FETCH tag.menu menu
			LEFT JOIN FETCH menu.menuCategory
			WHERE tag.record.member.id = :memberId
			AND tag.record.deletedAt IS NULL
			AND tag.record.eatenAt >= :from
			AND tag.record.eatenAt < :to
			""")
	List<BreadRecordTag> findTagsWithMenu(
			@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);

	/** 보고서에 선택된 사진의 현재 태그만 확인한다. 발행 당시 횟수와 순위를 재집계하지 않는다. */
	@Query("""
			SELECT tag
			FROM BreadRecordTag tag
			JOIN FETCH tag.record record
			JOIN FETCH tag.photo photo
			LEFT JOIN FETCH tag.menu menu
			LEFT JOIN FETCH menu.menuCategory
			WHERE record.member.id = :memberId AND record.deletedAt IS NULL
			AND photo.record.id = record.id
			AND (photo.referenceId IN :references OR photo.awsKey IN :keys
				OR photo.resizedKey320 IN :keys OR photo.resizedKey640 IN :keys OR photo.resizedKey1024 IN :keys)
			""")
	List<BreadRecordTag> findMonthlyReportPhotoTags(@Param("memberId") Long memberId,
			@Param("references") Set<UUID> references, @Param("keys") Set<String> keys);
}
