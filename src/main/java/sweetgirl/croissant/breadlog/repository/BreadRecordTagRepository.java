package sweetgirl.croissant.breadlog.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadlog.entity.BreadRecordTag;

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

	/** 월간 보고서의 종류별 집계와 대표 사진 선택. 사진·기록·메뉴 분류를 한 번에 읽는다. */
	@Query("""
			SELECT tag
			FROM BreadRecordTag tag
			JOIN FETCH tag.record record
			JOIN FETCH tag.photo photo
			LEFT JOIN FETCH tag.menu menu
			LEFT JOIN FETCH menu.menuCategory
			WHERE record.member.id = :memberId AND record.deletedAt IS NULL
			AND record.eatenAt >= :from AND record.eatenAt < :to
			AND photo.record.id = record.id
			ORDER BY CASE WHEN tag.name MEMBER OF record.lovedBreads OR tag.name = record.lovedBread THEN 0 ELSE 1 END,
				record.eatenAt DESC, record.id DESC, photo.sortOrder, photo.id, tag.id
			""")
	List<BreadRecordTag> findMonthlyReportTags(
			@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);
}
