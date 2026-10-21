package sweetgirl.croissant.personalization.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 원본 표(저장 · 계획 · 코스 · 기록 · 요청 로그 · 추천 반응)에서 사건을 user_store_interaction 으로 옮겨 적는다 (#663, 설계 §4.2).
 * 사실만 옮기고 해석(가중치 · 종류)은 옮기지 않는다. 범위는 사건 시각 [from, to) 이고, 유니크 제약(NULLS NOT DISTINCT)과
 * ON CONFLICT DO NOTHING 으로 같은 범위를 다시 돌려도 안전하다.
 *
 * <p>COURSE_ADD 는 여기 없다 — 담는 요청의 회원으로 이벤트가 남기고(InteractionEventListener), 이벤트 이전의 과거분만
 * 코스 주인 기준으로 한 번 백필한다(backfillCourseAddsByOwner). 저장 해제(UNSAVE)도 하드 삭제라 이벤트로만 남는다.
 */
@Repository
@RequiredArgsConstructor
public class UserInteractionIngestJdbcRepository {

	private static final String INSERT = """
			INSERT INTO user_store_interaction (member_id, store_id, menu_id, type, context, converted, occurred_at)
			""";
	private static final String ON_CONFLICT = """
			ON CONFLICT ON CONSTRAINT uq_user_store_interaction DO NOTHING
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/** 적재 전용 트랜잭션 advisory lock — 스케줄러와 관리자 수동 실행이 겹치지 않게. 같은 트랜잭션 안에서 부른다. */
	public boolean tryLock(long lockKey) {
		Boolean locked = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(:lockKey)",
			new MapSqlParameterSource("lockKey", lockKey), Boolean.class);
		return Boolean.TRUE.equals(locked);
	}

	public boolean isDayComplete(LocalDate day) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM user_interaction_ingest_day WHERE ingest_date = :day)
				""", new MapSqlParameterSource("day", day), Boolean.class));
	}

	public int insertSaves(LocalDateTime from, LocalDateTime to) {
		return jdbcTemplate.update(INSERT + """
				SELECT ss.member_id, ss.store_id, NULL, 'SAVE', NULL, NULL, ss.created_at
				FROM saved_stores ss
				WHERE ss.created_at >= :from AND ss.created_at < :to
				""" + ON_CONFLICT, range(from, to));
	}

	public int insertPlans(LocalDateTime from, LocalDateTime to) {
		return jdbcTemplate.update(INSERT + """
				SELECT sp.member_id, sp.store_id, NULL, 'PLAN', NULL, NULL, sp.created_at
				FROM store_plan sp
				WHERE sp.created_at >= :from AND sp.created_at < :to
				""" + ON_CONFLICT, range(from, to));
	}

	/** 기록의 가게. 사건 시각은 created_at — 사진첩 소급 등록이 많아 eaten_at 이 아니다(설계 §4.2). */
	public int insertRecordStores(LocalDateTime from, LocalDateTime to) {
		return jdbcTemplate.update(INSERT + """
				SELECT br.member_id, br.store_id, NULL, 'RECORD_STORE', NULL, NULL, br.created_at
				FROM bread_record br
				WHERE br.deleted_at IS NULL AND br.store_id IS NOT NULL
					AND br.created_at >= :from AND br.created_at < :to
				""" + ON_CONFLICT, range(from, to));
	}

	/** 기록의 빵 — 가게 메뉴에 연결된 태그만(menu_id 가 있어야 종류 → 축으로 갈 수 있다. 운영 1,146건 중 1,073건). */
	public int insertRecordBreads(LocalDateTime from, LocalDateTime to) {
		return jdbcTemplate.update(INSERT + """
				SELECT br.member_id, br.store_id, t.menu_id, 'RECORD_BREAD', NULL, NULL, br.created_at
				FROM bread_record_tag t
					JOIN bread_record br ON br.id = t.record_id
				WHERE br.deleted_at IS NULL AND t.menu_id IS NOT NULL
					AND br.created_at >= :from AND br.created_at < :to
				""" + ON_CONFLICT, range(from, to));
	}

	/** 일별 적재 전체가 성공한 뒤 호출한다. 아직 끝나지 않은 날짜는 호출자가 제외한다. */
	public int recordCompletedDays(LocalDateTime from, LocalDateTime until) {
		return jdbcTemplate.update("""
				INSERT INTO user_interaction_ingest_day (ingest_date, completed_at)
				SELECT CAST(day AS date), clock_timestamp()
				FROM generate_series(CAST(:from AS timestamp), CAST(:to AS timestamp) - interval '1 day',
					interval '1 day') AS day
				ON CONFLICT (ingest_date) DO UPDATE SET completed_at = EXCLUDED.completed_at
				""", range(from, until));
	}

	/**
	 * 가게 상세 조회 — 회원 · 가게 · 날짜당 한 행(첫 조회 시각). converted = 첫 조회 뒤 windowMinutes 안에 같은 가게를
	 * 저장 · 담기 · 계획했는가. 익명(member_id NULL)은 빼고, STORE_VIEW 부분 인덱스(#741)를 탄다.
	 */
	public int insertStoreViews(LocalDateTime from, LocalDateTime to, int windowMinutes) {
		return jdbcTemplate.update("""
				WITH views AS (
					SELECT rl.member_id, rl.store_id, MIN(rl.created_at) AS first_at,
						MIN(rl.created_at) + make_interval(mins => :windowMinutes) AS window_end
					FROM request_log rl
					WHERE rl.event_type = 'STORE_VIEW' AND rl.store_id IS NOT NULL AND rl.member_id IS NOT NULL
						AND rl.created_at >= :from AND rl.created_at < :to
					GROUP BY rl.member_id, rl.store_id, CAST(rl.created_at AS date)
				)
				""" + INSERT
			+ """
					SELECT v.member_id, v.store_id, NULL, 'STORE_VIEW', NULL,
						EXISTS (SELECT 1 FROM saved_stores ss
							WHERE ss.member_id = v.member_id AND ss.store_id = v.store_id
								AND ss.created_at >= v.first_at AND ss.created_at < v.window_end)
						OR EXISTS (SELECT 1 FROM store_plan sp
							WHERE sp.member_id = v.member_id AND sp.store_id = v.store_id
								AND sp.created_at >= v.first_at AND sp.created_at < v.window_end)
						OR EXISTS (SELECT 1 FROM course_stores cs JOIN courses c ON c.id = cs.course_id
							WHERE c.member_id = v.member_id AND cs.store_id = v.store_id
							AND cs.created_at >= v.first_at AND cs.created_at < v.window_end),
						v.first_at
					FROM views v
					"""
			+ ON_CONFLICT, range(from, to).addValue("windowMinutes", windowMinutes));
	}

	/**
	 * 담았다가 뺌 — course_stores 에 뺀 사람이 없어 코스 주인 기준(#732 S4, 협업 코스에서는 어긋날 수 있다).
	 * 큐레이션 코스는 주인이 없다(courses.member_id NULL, 운영 38개) — 누구의 신호도 아니므로 뺀다(PR #747 리뷰, NOT NULL 위반).
	 */
	public int insertCourseRemoves(LocalDateTime from, LocalDateTime to) {
		return jdbcTemplate.update(INSERT + """
				SELECT c.member_id, cs.store_id, NULL, 'COURSE_REMOVE', NULL, NULL, cs.deleted_at
				FROM course_stores cs
					JOIN courses c ON c.id = cs.course_id
				WHERE c.member_id IS NOT NULL
					AND cs.deleted_at IS NOT NULL AND cs.deleted_at >= :from AND cs.deleted_at < :to
				""" + ON_CONFLICT, range(from, to));
	}

	/**
	 * 추천 시트를 담지 않고 닫음 — 그 묶음에 보인 가게마다 한 행. 사건 시각을 묶음(recommendation)의 시각으로 두어 같은 묶음을
	 * 여러 번 닫아도(재사용) 묶음당 한 번만 센다(#663 TODO "노출 후 안 담김 −0.3 은 묶음당 한 번"). 그 묶음에서 실제로 담은 가게
	 * (course_stores.recommendation_id)는 "안 담은 것"이 아니라 뺀다 — 담기 +1.5 와 상쇄돼 신호가 흐려지지 않게(PR #747 리뷰).
	 */
	public int insertRecoDismissed(LocalDateTime from, LocalDateTime to) {
		return jdbcTemplate.update(INSERT + """
				SELECT DISTINCT r.member_id, CAST(shown->>'storeId' AS bigint), CAST(NULL AS bigint), 'RECO_DISMISSED',
					'RECO', CAST(NULL AS boolean), r.created_at
				FROM recommendation_feedback f
					JOIN recommendation r ON r.id = f.recommendation_id
					CROSS JOIN LATERAL jsonb_array_elements(r.result->'stores') shown
				WHERE f.action = 'DISMISS' AND f.created_at >= :from AND f.created_at < :to
					AND NOT EXISTS (SELECT 1 FROM course_stores cs
						WHERE cs.recommendation_id = r.id AND cs.store_id = CAST(shown->>'storeId' AS bigint))
				""" + ON_CONFLICT, range(from, to));
	}

	public int insertRecoUnchecks(LocalDateTime from, LocalDateTime to) {
		return jdbcTemplate.update(INSERT + """
				SELECT r.member_id, f.store_id, NULL, 'RECO_UNCHECK', 'RECO', NULL, f.created_at
				FROM recommendation_feedback f
					JOIN recommendation r ON r.id = f.recommendation_id
				WHERE f.action = 'STORE_UNCHECK' AND f.store_id IS NOT NULL
					AND f.created_at >= :from AND f.created_at < :to
				""" + ON_CONFLICT, range(from, to));
	}

	/**
	 * 담기 백필 — 이벤트가 붙기 전에 담긴 가게를 코스 주인 기준으로 한 번 옮긴다(09-18 결정: 앞으로는 담은 회원, 과거분은 코스 주인).
	 * until 이후 행은 이벤트가 남기므로 여기서 다시 넣으면 협업 코스에서 주인에게 잘못 귀속된다 — 한 번만, until 을 배포 시각으로.
	 */
	public int backfillCourseAddsByOwner(LocalDateTime until) {
		return jdbcTemplate.update(INSERT + """
				SELECT c.member_id, cs.store_id, NULL, 'COURSE_ADD',
					CASE WHEN cs.recommendation_id IS NOT NULL THEN 'RECO' END, NULL, cs.created_at
				FROM course_stores cs
					JOIN courses c ON c.id = cs.course_id
				WHERE c.member_id IS NOT NULL AND cs.created_at < :until
				""" + ON_CONFLICT, new MapSqlParameterSource("until", until));
	}

	private static MapSqlParameterSource range(LocalDateTime from, LocalDateTime to) {
		return new MapSqlParameterSource().addValue("from", from).addValue("to", to);
	}
}
