package sweetgirl.croissant.breadlog.repository;

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
}
