package sweetgirl.croissant.breadlog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadlog.entity.MemberTutorialCompletion;

public interface MemberTutorialCompletionRepository extends JpaRepository<MemberTutorialCompletion, Long> {
	@Modifying
	@Query(value = """
			DELETE FROM member_tutorial_completion guest WHERE guest.member_id = :guestId
			AND EXISTS (SELECT 1 FROM member_tutorial_completion target WHERE target.member_id = :targetId)
			""", nativeQuery = true)
	void removeMergedCompletion(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying
	@Query(value = """
			UPDATE member_tutorial_completion SET member_id = :targetId WHERE member_id = :guestId
			""", nativeQuery = true)
	void moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}
