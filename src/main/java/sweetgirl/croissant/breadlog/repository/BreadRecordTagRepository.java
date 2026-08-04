package sweetgirl.croissant.breadlog.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadlog.entity.BreadRecordTag;

public interface BreadRecordTagRepository extends JpaRepository<BreadRecordTag, Long> {

	@Query("""
			SELECT tag
			FROM BreadRecordTag tag
			JOIN FETCH tag.photo photo
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

	@Query("""
			SELECT DISTINCT tag.breadCategory
			FROM BreadRecordTag tag
			WHERE tag.record.member.id = :memberId
			AND tag.record.deletedAt IS NULL
			AND tag.record.eatenAt >= :from
			AND tag.record.eatenAt < :to
			AND tag.breadCategory <> '기타'
			ORDER BY tag.breadCategory
			""")
	List<String> findBreadCategories(
			@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);
}
