package sweetgirl.croissant.monthlyreport.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.monthlyreport.entity.MonthlyReportSourceHistory;

public interface MonthlyReportSourceHistoryRepository extends JpaRepository<MonthlyReportSourceHistory, Long> {

	@Query(value = "SELECT covered_from FROM monthly_report_history_coverage WHERE id = 1", nativeQuery = true)
	LocalDateTime findCoveredFrom();

	/** 이력 트리거와 같은 DB 시계·시간대로 최초 도입 월의 조회 경계를 정한다. */
	@Query(value = "SELECT clock_timestamp() AT TIME ZONE 'Asia/Seoul'", nativeQuery = true)
	LocalDateTime findSnapshotTime();

	/** 도입 전 월: 성공한 요청·추천 신호·남은 관계를 합쳐 저장/계획의 추가와 해제를 재생한다. */
	@Query(value = """
			WITH events AS (
				SELECT 'SAVE' AS kind, store_id, TRUE AS active, created_at AS happened_at, 0 AS origin, id
				FROM saved_stores WHERE member_id = :memberId AND created_at < :until
				UNION ALL
				SELECT 'PLAN', store_id, TRUE, created_at, 0, store_id
				FROM store_plan WHERE member_id = :memberId AND created_at < :until
				UNION ALL
				SELECT CASE WHEN type = 'PLAN' THEN 'PLAN' ELSE 'SAVE' END,
					store_id, type <> 'UNSAVE', occurred_at, 1, id
				FROM user_store_interaction
				WHERE member_id = :memberId AND store_id IS NOT NULL
					AND type IN ('SAVE', 'UNSAVE', 'PLAN') AND occurred_at < :until
				UNION ALL
				SELECT CASE WHEN endpoint ~ '^(/api)?/stores/' THEN 'SAVE' ELSE 'PLAN' END,
					CAST(substring(endpoint FROM '/([0-9]+)(/save)?$') AS bigint),
					http_method <> 'DELETE', created_at, 2, id
				FROM request_log
				WHERE member_id = :memberId AND created_at < :until
					AND response_status >= 200 AND response_status < 300
					AND ((endpoint ~ '^(/api)?/stores/[0-9]+/save$' AND http_method IN ('POST', 'DELETE'))
						OR (endpoint ~ '^(/api)?/store-plans/[0-9]+$' AND http_method IN ('PUT', 'DELETE')))
			), ordered AS (
				SELECT *, lag(active) OVER (PARTITION BY kind, store_id
					ORDER BY happened_at, active DESC, origin, id) AS previous_active
				FROM events
			), latest_change AS (
				SELECT DISTINCT ON (kind, store_id) * FROM ordered
				WHERE previous_active IS DISTINCT FROM active
				ORDER BY kind, store_id, happened_at DESC, active, origin DESC, id DESC
			)
			SELECT kind AS "relationType", store_id AS "storeId" FROM latest_change
			WHERE active AND happened_at >= :from
			""", nativeQuery = true)
	List<InitialRelation> findInitialMonthEndRelations(@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

	interface InitialRelation {
		String getRelationType();

		Long getStoreId();
	}

	/** 최신 버전 판정에는 다른 소유자의 버전과 삭제 표식도 포함해야 이관·삭제 전 상태가 살아나지 않는다. */
	@Query(value = """
			SELECT h.* FROM monthly_report_source_history h
			WHERE h.member_id = :memberId
			AND h.source_type IN ('BREAD_RECORD', 'SAVED_STORE', 'STORE_PLAN', 'COURSE')
			AND h.recorded_at < :until AND h.payload IS NOT NULL
			AND NOT EXISTS (SELECT 1 FROM monthly_report_source_history newer
				WHERE newer.source_type = h.source_type AND newer.source_key = h.source_key
				AND newer.recorded_at < :until
				AND (newer.recorded_at, newer.id) > (h.recorded_at, h.id))
			""", nativeQuery = true)
	List<MonthlyReportSourceHistory> findOwnerState(@Param("memberId") Long memberId,
			@Param("until") LocalDateTime until);

	/** 전월 비교에 필요한 빵로그 상태만 읽는다. 삭제·소유권 변경을 포함해 마지막 이력을 고른다. */
	@Query(value = """
			SELECT h.* FROM monthly_report_source_history h
			WHERE h.member_id = :memberId AND h.source_type = 'BREAD_RECORD'
			AND h.recorded_at < :until AND h.payload IS NOT NULL
			AND NOT EXISTS (SELECT 1 FROM monthly_report_source_history newer
				WHERE newer.source_type = h.source_type AND newer.source_key = h.source_key
				AND newer.recorded_at < :until
				AND (newer.recorded_at, newer.id) > (h.recorded_at, h.id))
			""", nativeQuery = true)
	List<MonthlyReportSourceHistory> findRecordState(@Param("memberId") Long memberId,
			@Param("until") LocalDateTime until);

	/** 자식은 월말 부모 소유권으로 범위를 정한다. 현재 자식의 작성자/소유권으로 거르지 않는다. */
	@Query(value = """
			SELECT h.* FROM monthly_report_source_history h
			WHERE h.source_type = :sourceType AND h.parent_id IN (:parentIds)
			AND h.recorded_at < :until AND h.payload IS NOT NULL
			AND NOT EXISTS (SELECT 1 FROM monthly_report_source_history newer
				WHERE newer.source_type = h.source_type AND newer.source_key = h.source_key
				AND newer.recorded_at < :until
				AND (newer.recorded_at, newer.id) > (h.recorded_at, h.id))
			""", nativeQuery = true)
	List<MonthlyReportSourceHistory> findChildState(@Param("sourceType") String sourceType,
			@Param("parentIds") Set<Long> parentIds, @Param("until") LocalDateTime until);
}
