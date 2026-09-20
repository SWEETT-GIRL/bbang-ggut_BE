package sweetgirl.croissant.world.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberCharacterId;

public interface MemberCharacterRepository
		extends JpaRepository<MemberCharacter, MemberCharacterId> {

	List<MemberCharacter> findAllByIdMemberId(Long memberId);

	/** 이미 가진 친구면 건드리지 않는다 — 획득 경로와 시각을 덮어쓰지 않기 위해서다. */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO member_characters
				(member_id, character_code, status, ad_views, acquired_by, acquired_at)
			VALUES (:memberId, :characterCode, 'AWAKE', 0, :acquiredBy, CURRENT_TIMESTAMP)
			ON CONFLICT (member_id, character_code) DO NOTHING
			""", nativeQuery = true)
	int grantIfAbsent(
			@Param("memberId") Long memberId,
			@Param("characterCode") String characterCode,
			@Param("acquiredBy") String acquiredBy);
}
