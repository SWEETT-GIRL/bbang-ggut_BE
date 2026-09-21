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

	/**
	 * 광고 한 편을 적립한다. 행이 없으면 자고 있는 상태로 만들면서 1 부터 센다.
	 *
	 * <p>읽어서 더하고 쓰면 두 요청이 같은 값을 읽어 한 편이 사라진다. 한 문장으로 올린다.
	 * 이미 깨어난 친구면 {@code WHERE} 가 걸러 0 행이 되므로, 반환값 0 = 이미 깨어남이다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO member_characters
				(member_id, character_code, status, ad_views, acquired_by, acquired_at)
			VALUES (:memberId, :characterCode, 'SLEEPING', 1, NULL, NULL)
			ON CONFLICT (member_id, character_code) DO UPDATE
			SET ad_views = member_characters.ad_views + 1
			WHERE member_characters.status = 'SLEEPING'
			""", nativeQuery = true)
	int addAdView(@Param("memberId") Long memberId, @Param("characterCode") String characterCode);

	/**
	 * 자고 있던 친구를 깨운다. 두 요청이 나란히 "이번이 마지막 편"이라 봐도 한쪽만 1 을 받는다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE member_characters
			SET status = 'AWAKE', acquired_by = :acquiredBy, acquired_at = CURRENT_TIMESTAMP
			WHERE member_id = :memberId AND character_code = :characterCode AND status = 'SLEEPING'
			""", nativeQuery = true)
	int awakeIfSleeping(
			@Param("memberId") Long memberId,
			@Param("characterCode") String characterCode,
			@Param("acquiredBy") String acquiredBy);

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
