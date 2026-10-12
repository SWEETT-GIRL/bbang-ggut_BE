package sweetgirl.croissant.world.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.world.entity.MemberWorld;

public interface MemberWorldRepository extends JpaRepository<MemberWorld, Long> {

	/**
	 * 게스트가 쌓아둔 여정을 회원 행에 합친다. 높이는 큰 쪽, 첫 친구를 본 때는 이른 쪽을 남긴다.
	 * 데리고 다닐 친구는 회원이 고른 것을 그대로 둔다 — 로그인한 계정의 선택이 정본이다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE member_world target
			SET max_record_count = GREATEST(target.max_record_count, guest.max_record_count),
				welcomed_at = CASE
					WHEN target.welcomed_at IS NULL THEN guest.welcomed_at
					WHEN guest.welcomed_at IS NULL THEN target.welcomed_at
					ELSE LEAST(target.welcomed_at, guest.welcomed_at)
				END,
				updated_at = CURRENT_TIMESTAMP
			FROM member_world guest
			WHERE target.member_id = :targetId AND guest.member_id = :guestId
			""", nativeQuery = true)
	int mergeGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	/** 합친 뒤 게스트 행을 지운다. 회원에게 행이 없으면 지우지 않고 그대로 옮긴다. */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			DELETE FROM member_world guest
			WHERE guest.member_id = :guestId
			AND EXISTS (SELECT 1 FROM member_world target WHERE target.member_id = :targetId)
			""", nativeQuery = true)
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query(value = "UPDATE member_world SET member_id = :targetId WHERE member_id = :guestId", nativeQuery = true)
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	/** 홈에 놓은 소품. 없으면 비어 있다. */
	@Query(value = "SELECT home_prop_item FROM member_world WHERE member_id = :memberId", nativeQuery = true)
	Optional<String> findHomePropItem(@Param("memberId") Long memberId);

	/** 홈 소품을 바꾼다(null 이면 치운다). 세계관을 시작하지 않은 회원이면 0 이다. */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE member_world SET home_prop_item = :itemCode, updated_at = CURRENT_TIMESTAMP
			WHERE member_id = :memberId
			""", nativeQuery = true)
	int updateHomePropItem(@Param("memberId") Long memberId, @Param("itemCode") String itemCode);

	/** 저장된 최고 높이. 아직 세계관에 들어온 적이 없으면 비어 있다. */
	@Query(value = "SELECT max_record_count FROM member_world WHERE member_id = :memberId", nativeQuery = true)
	Optional<Integer> findMaxRecordCount(@Param("memberId") Long memberId);

	/**
	 * 빵탑의 최고 높이를 갱신한다. 기록을 지워도 행성이 안 밀리게 하는 값이라 <b>내려가지 않는다</b>.
	 *
	 * <p>아직 세계관 화면에 한 번도 안 들어온 회원은 행이 없어 0 행이 된다. 문제가 아니다 —
	 * 첫 조회에서 지금 기록 수로 높이를 다시 잡는다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE member_world
			SET max_record_count = GREATEST(max_record_count, :recordCount),
				updated_at = CURRENT_TIMESTAMP
			WHERE member_id = :memberId
			""", nativeQuery = true)
	int raiseMaxRecordCount(@Param("memberId") Long memberId, @Param("recordCount") long recordCount);

	/**
	 * 첫 조회에서 여정을 시작한다. 가입 경로가 넷이라 가입 훅 대신 여기서 만든다.
	 *
	 * <p>같은 회원의 첫 조회가 동시에 둘 들어오면 양쪽 다 "없다"를 보고 넣으려 든다.
	 * 뒤에 온 쪽이 PK 로 깨지지 않도록 넣기를 건너뛴다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO member_world (member_id, selected_character, max_record_count, created_at)
			VALUES (:memberId, :characterCode, 0, CURRENT_TIMESTAMP)
			ON CONFLICT (member_id) DO NOTHING
			""", nativeQuery = true)
	int startIfAbsent(@Param("memberId") Long memberId, @Param("characterCode") String characterCode);
}
