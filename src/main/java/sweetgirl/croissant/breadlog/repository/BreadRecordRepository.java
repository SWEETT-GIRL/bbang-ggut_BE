package sweetgirl.croissant.breadlog.repository;

import java.time.LocalDateTime;
import java.util.Collection;
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
	 *
	 * <p>날짜를 {@code yyyy-MM-dd} 글자로 받는다. 네이티브 쿼리의 DATE 는 Hibernate 버전·설정에 따라
	 * {@code java.sql.Date} 로도 {@code LocalDate} 로도 오는데, {@code List<java.sql.Date>} 로 받던 때에는
	 * 실제로는 {@code LocalDate} 가 담겨 와 꺼내는 순간 형 변환에서 터졌다(요약 조회 500).
	 */
	@Query(value = """
			SELECT DISTINCT CAST(CAST(created_at AS DATE) AS VARCHAR) AS recorded_on
			FROM bread_record
			WHERE member_id = :memberId AND deleted_at IS NULL AND record_type = 'NORMAL' AND created_at >= :since
			ORDER BY recorded_on DESC
			""", nativeQuery = true)
	List<String> findRecordedDaysSince(@Param("memberId") Long memberId, @Param("since") LocalDateTime since);

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
			LEFT JOIN FETCH record.store
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
	// 사진이 없어도 내린다(2.1.0 강제 업데이트 뒤 — 2.0.x 는 사진 없는 기록을 열면 꺼졌다).
	@Query("""
			SELECT record
			FROM BreadRecord record
			JOIN FETCH record.member member
			WHERE record.store.id = :storeId
			AND record.isPublic = true
			AND record.deletedAt IS NULL
			AND record.hiddenAt IS NULL
			AND member.deletedAt IS NULL
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

	/** 가게 공개 빵로그 전체 수 — 목록과 같은 조건(숨김 제외 · 보는 사람이 차단한 작성자 제외). */
	@Query("""
			SELECT COUNT(record)
			FROM BreadRecord record
			JOIN record.member member
			WHERE record.store.id = :storeId
			AND record.isPublic = true
			AND record.deletedAt IS NULL
			AND record.hiddenAt IS NULL
			AND member.deletedAt IS NULL
			AND (:viewerMemberId IS NULL OR NOT EXISTS (
				SELECT 1 FROM MemberBlock mb
				WHERE mb.blocker.id = :viewerMemberId
				AND mb.blockedMember.id = member.id
			))
			""")
	long countPublicByStoreId(@Param("storeId") Long storeId, @Param("viewerMemberId") Long viewerMemberId);

	/**
	 * 가게 공개 빵로그 — 추천순 id. 프로필 추천순과 같은 점수((좋아요 + 댓글 × 2) × 날짜 가중), 조건은 최신순 목록과 같다.
	 * 네이티브라 null 보는 사람은 CAST 로 타입을 준다.
	 */
	@Query(value = """
			SELECT r.id
			FROM bread_record r
			JOIN member m ON m.id = r.member_id AND m.deleted_at IS NULL
			LEFT JOIN (SELECT record_id, COUNT(*) AS like_count FROM record_like GROUP BY record_id) l
				ON l.record_id = r.id
			LEFT JOIN (SELECT record_id, COUNT(*) AS comment_count FROM record_comment
				WHERE deleted_at IS NULL AND hidden_at IS NULL GROUP BY record_id) c
				ON c.record_id = r.id
			WHERE r.store_id = :storeId AND r.is_public = true AND r.deleted_at IS NULL AND r.hidden_at IS NULL
				AND (CAST(:viewerMemberId AS BIGINT) IS NULL OR NOT EXISTS (
					SELECT 1 FROM member_block mb
					WHERE mb.blocker_id = CAST(:viewerMemberId AS BIGINT) AND mb.blocked_member_id = r.member_id))
			ORDER BY (COALESCE(l.like_count, 0) + COALESCE(c.comment_count, 0) * 2)
					* CASE WHEN r.created_at >= :recent THEN 1.0 WHEN r.created_at >= :older THEN 0.6 ELSE 0.3 END DESC,
				r.eaten_at DESC, r.id DESC
			LIMIT :limit OFFSET :offset
			""", nativeQuery = true)
	List<Long> findPublicIdsByStoreIdRecommended(@Param("storeId") Long storeId,
			@Param("viewerMemberId") Long viewerMemberId, @Param("recent") LocalDateTime recent,
			@Param("older") LocalDateTime older, @Param("limit") int limit, @Param("offset") int offset);

	/** 다녀간 빵집 지도 — 가게를 고른 공개 빵로그 전부(최신순). 가게별 묶기는 서비스가 한다. */
	@Query("""
			SELECT r FROM BreadRecord r
			JOIN FETCH r.store store
			WHERE r.member.id = :memberId AND r.isPublic = true AND r.deletedAt IS NULL AND r.hiddenAt IS NULL
			ORDER BY r.eatenAt DESC, r.id DESC
			""")
	List<BreadRecord> findPublicWithStoreByMemberId(@Param("memberId") Long memberId);

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

	/** 프로필의 공개 빵로그 수. */
	@Query("""
			SELECT COUNT(r) FROM BreadRecord r
			WHERE r.member.id = :memberId AND r.isPublic = true AND r.deletedAt IS NULL AND r.hiddenAt IS NULL
			""")
	long countPublicByMemberId(@Param("memberId") Long memberId);

	/** 보는 사람이 신고해 안 보이는 빵로그를 뺀 수 — 프로필의 수와 목록이 어긋나지 않게. */
	@Query("""
			SELECT COUNT(r) FROM BreadRecord r
			WHERE r.member.id = :memberId AND r.isPublic = true AND r.deletedAt IS NULL AND r.hiddenAt IS NULL
				AND r.id NOT IN :excludedIds
			""")
	long countPublicByMemberIdExcluding(@Param("memberId") Long memberId,
			@Param("excludedIds") Collection<Long> excludedIds);

	/** 칭호용 — 공개 빵로그가 가장 많은 동네(region_key) 하나. {@code minCount} 개 미만이면 비어 있다. */
	@Query(value = """
			SELECT r.region_key AS regionKey, COUNT(*) AS recordCount
			FROM bread_record r
			WHERE r.member_id = :memberId AND r.is_public = true AND r.deleted_at IS NULL AND r.hidden_at IS NULL
				AND r.region_key IS NOT NULL
			GROUP BY r.region_key
			HAVING COUNT(*) >= :minCount
			ORDER BY COUNT(*) DESC, r.region_key ASC
			LIMIT 1
			""", nativeQuery = true)
	Optional<BreadRecordTownProjection> findTopPublicTown(@Param("memberId") Long memberId,
			@Param("minCount") int minCount);

	/**
	 * 추천 후보 — {@code since} 뒤에 공개 빵로그를 남긴 일반 회원, 보는 사람과 같은 빵집을 기록한 사람 우선, 그다음 최근순.
	 * 보는 사람 본인은 뺀다. 팔로우 중 · 차단은 부르는 쪽이 거른다.
	 */
	@Query(value = """
			SELECT r.member_id
			FROM bread_record r
			JOIN member m ON m.id = r.member_id AND m.deleted_at IS NULL AND m.member_type = 'USER'
			WHERE r.is_public = true AND r.deleted_at IS NULL AND r.hidden_at IS NULL
				AND r.created_at >= :since AND r.member_id <> :viewerId
				AND NOT EXISTS (SELECT 1 FROM member_restriction mr
					WHERE mr.member_id = r.member_id AND mr.restriction_type = 'PROFILE_RESTRICTED'
						AND mr.starts_at <= CURRENT_TIMESTAMP
						AND (mr.ends_at IS NULL OR mr.ends_at > CURRENT_TIMESTAMP))
			GROUP BY r.member_id
			ORDER BY COUNT(*) FILTER (WHERE r.store_id IN (
					SELECT mine.store_id FROM bread_record mine
					WHERE mine.member_id = :viewerId AND mine.deleted_at IS NULL AND mine.store_id IS NOT NULL)) DESC,
				MAX(r.created_at) DESC
			LIMIT :limit
			""", nativeQuery = true)
	List<Long> findRecentPublicRecorderIds(@Param("viewerId") Long viewerId, @Param("since") LocalDateTime since,
			@Param("limit") int limit);

	/**
	 * 프로필의 공개 빵로그 — 최신순(먹은 날). 본인({@code includeHidden})은 숨긴 것도 본다.
	 * 사진이 없어도 내린다(새 앱 화면).
	 */
	@Query("""
			SELECT r FROM BreadRecord r
			WHERE r.member.id = :memberId AND r.isPublic = true AND r.deletedAt IS NULL
				AND (:includeHidden = true OR r.hiddenAt IS NULL)
			ORDER BY r.eatenAt DESC, r.id DESC
			""")
	Slice<BreadRecord> findPublicByMemberIdLatest(@Param("memberId") Long memberId,
			@Param("includeHidden") boolean includeHidden, Pageable pageable);

	/**
	 * 프로필의 공개 빵로그 — 추천순 id. 점수 = (좋아요 + 댓글 × 2) × 날짜 가중(7일 안 1.0 / 30일 안 0.6 / 그 뒤 0.3),
	 * 동점은 최신. 점수가 시간에 따라 변해 커서 대신 offset 이다.
	 */
	@Query(value = """
			SELECT r.id
			FROM bread_record r
			LEFT JOIN (SELECT record_id, COUNT(*) AS like_count FROM record_like GROUP BY record_id) l
				ON l.record_id = r.id
			LEFT JOIN (SELECT record_id, COUNT(*) AS comment_count FROM record_comment
				WHERE deleted_at IS NULL AND hidden_at IS NULL GROUP BY record_id) c
				ON c.record_id = r.id
			WHERE r.member_id = :memberId AND r.is_public = true AND r.deleted_at IS NULL
				AND (:includeHidden = true OR r.hidden_at IS NULL)
			ORDER BY (COALESCE(l.like_count, 0) + COALESCE(c.comment_count, 0) * 2)
					* CASE WHEN r.created_at >= :recent THEN 1.0 WHEN r.created_at >= :older THEN 0.6 ELSE 0.3 END DESC,
				r.created_at DESC, r.id DESC
			LIMIT :limit OFFSET :offset
			""", nativeQuery = true)
	List<Long> findPublicIdsByMemberIdRecommended(@Param("memberId") Long memberId,
			@Param("includeHidden") boolean includeHidden, @Param("recent") LocalDateTime recent,
			@Param("older") LocalDateTime older, @Param("limit") int limit, @Param("offset") int offset);
}
