package sweetgirl.croissant.breadlog.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import sweetgirl.croissant.breadlog.dto.projection.BreadRecordRegionProjection;
import sweetgirl.croissant.breadlog.dto.projection.BreadRecordStoreVisitProjection;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.enums.RecordType;

public interface BreadRecordRepository extends JpaRepository<BreadRecord, Long> {

	Optional<BreadRecord> findByIdAndDeletedAtIsNull(Long id);

	/**
	 * 기록과 그 사진·태그를 바꾸는 쓰기(수정·삭제·공개 전환)의 시작점. 같은 기록의 쓰기를 한 줄로 세우고
	 * 락 순서를 기록 → 사진으로 맞춘다. 조회에는 걸지 않는다 — findByIdAndDeletedAtIsNull 그대로.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<BreadRecord> findWithLockByIdAndDeletedAtIsNull(Long id);

	// 코스에서 "다녀왔어요"로 고를 후보 — 그 가게의 내 기록 전부, 최신순.
	List<BreadRecord> findAllByMemberIdAndStoreIdAndDeletedAtIsNullOrderByEatenAtDesc(
			Long memberId, Long storeId);

	long countByMemberIdAndDeletedAtIsNull(Long memberId);

	/** 같은 저장 요청으로 이미 만든 기록. 지운 기록도 찾는다 — 재전송이 지운 기록을 되살려 또 만들지 않게. */
	Optional<BreadRecord> findByMemberIdAndClientRequestId(Long memberId, UUID clientRequestId);

	/**
	 * 보통 기록을 남긴 날들(저장한 날 기준, 최근 것부터). 1.x 홈의 "N일 연속"과 같은 기준이다 — 먹은 날이 아니라 저장한 날.
	 */
	@Query(value = """
			SELECT DISTINCT CAST(created_at AS DATE) AS recorded_on
			FROM bread_record
			WHERE member_id = :memberId AND deleted_at IS NULL AND record_type = 'NORMAL' AND created_at >= :since
			ORDER BY recorded_on DESC
			""", nativeQuery = true)
	List<java.sql.Date> findRecordedDaysSince(@Param("memberId") Long memberId, @Param("since") LocalDateTime since);

	/** 종류를 가려 센다. 리워드는 체험(TUTORIAL) 기록을 세지 않는다. */
	long countByMemberIdAndRecordTypeAndDeletedAtIsNull(Long memberId, RecordType recordType);

	@Modifying
	@Query(value = """
			UPDATE bread_record SET deleted_at = CURRENT_TIMESTAMP
			WHERE member_id = :guestId AND record_type = 'TUTORIAL' AND deleted_at IS NULL
			AND EXISTS (SELECT 1 FROM member_tutorial_completion WHERE member_id = :targetId)
			""", nativeQuery = true)
	void retireDuplicateTutorial(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	/** 먹어 본 가게인가 — 그 가게에 안 지운 빵로그가 하나라도 있으면. 내 빵 순위에 담을 수 있는 기준. */
	boolean existsByMemberIdAndStoreIdAndDeletedAtIsNull(Long memberId, Long storeId);

	/**
	 * 빵로그를 남긴(안 지운) 가게 id — 내 빵 순위에 담을 후보. 가게 없는 기록은 뺀다.
	 * id 만 뽑는 이유: Store 엔티티를 DISTINCT 하면 geography 컬럼까지 비교 대상이 된다. 상태·이름 필터는 가게를 읽은 뒤 건다.
	 */
	@Query("""
			SELECT DISTINCT record.store.id
			FROM BreadRecord record
			WHERE record.member.id = :memberId AND record.deletedAt IS NULL AND record.store IS NOT NULL
			""")
	List<Long> findEatenStoreIds(@Param("memberId") Long memberId);

	@Query(value = """
			SELECT COALESCE(MAX(visits), 0)
			FROM (
				SELECT COUNT(*) AS visits
				FROM bread_record
				WHERE member_id = :memberId
				AND deleted_at IS NULL
				AND record_type = 'NORMAL'
				GROUP BY CASE
					WHEN store_id IS NOT NULL THEN 'id:' || CAST(store_id AS VARCHAR)
					ELSE 'name:' || store_name
				END
			) store_visits
			""", nativeQuery = true)
	long findMaxStoreVisitCount(@Param("memberId") Long memberId);

	@Query("""
			SELECT record
			FROM BreadRecord record
			WHERE record.member.id = :memberId
			AND record.deletedAt IS NULL
			AND (CAST(:from AS LocalDateTime) IS NULL OR record.eatenAt >= :from)
			AND (CAST(:to AS LocalDateTime) IS NULL OR record.eatenAt < :to)
			AND (CAST(:storeId AS Long) IS NULL OR record.store.id = :storeId)
			""")
	Slice<BreadRecord> findRecords(
			@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to,
			@Param("storeId") Long storeId,
			Pageable pageable);

	// 가게 상세에 쌓이는 공개 기록 — 탈퇴한 작성자와 보는 사람이 차단한 작성자는 뺀다.
	// 사진이 없는 기록은 공개로 저장했어도 남에게 내리지 않는다(옛 앱은 사진 없는 기록을 열면 꺼진다).
	@Query("""
			SELECT record
			FROM BreadRecord record
			JOIN FETCH record.member member
			WHERE record.store.id = :storeId
			AND record.isPublic = true
			AND record.deletedAt IS NULL
			AND member.deletedAt IS NULL
			AND EXISTS (SELECT 1 FROM BreadRecordPhoto photo WHERE photo.record.id = record.id)
			AND (:viewerMemberId IS NULL OR NOT EXISTS (
				SELECT 1 FROM MemberBlock mb
				WHERE mb.blocker.id = :viewerMemberId
				AND mb.blockedMember.id = member.id
			))
			ORDER BY record.eatenAt DESC, record.id DESC
			""")
	List<BreadRecord> findPublicByStoreId(
			@Param("storeId") Long storeId,
			@Param("viewerMemberId") Long viewerMemberId,
			Pageable pageable);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE BreadRecord record SET record.member.id = :targetId WHERE record.member.id = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Query(value = """
			SELECT
				record.store_id AS storeId,
				MIN(record.store_name) AS name,
				COUNT(*) AS visits
			FROM bread_record record
			WHERE record.member_id = :memberId
			AND record.deleted_at IS NULL
			AND record.record_type = 'NORMAL'
			AND record.eaten_at >= :from
			AND record.eaten_at < :to
			GROUP BY
				record.store_id,
				CASE WHEN record.store_id IS NULL
					THEN LOWER(REGEXP_REPLACE(record.store_name, '[[:space:]]+', '', 'g'))
					ELSE NULL
				END
			ORDER BY COUNT(*) DESC, MIN(record.store_name)
			""", nativeQuery = true)
	List<BreadRecordStoreVisitProjection> findStoreVisits(
			@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);

	@Query(value = """
			SELECT DISTINCT
				store.region_1st AS region1st,
				record.region_key AS regionKey
			FROM bread_record record
			LEFT JOIN store ON store.id = record.store_id
			WHERE record.member_id = :memberId
			AND record.deleted_at IS NULL
			AND record.record_type = 'NORMAL'
			AND record.eaten_at >= :from
			AND record.eaten_at < :to
			""", nativeQuery = true)
	List<BreadRecordRegionProjection> findVisitedRegions(
			@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);
}
