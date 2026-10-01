package sweetgirl.croissant.breadlog.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadlog.dto.projection.RankingRowProjection;
import sweetgirl.croissant.breadlog.entity.BreadRecord;

/**
 * 빵로그 랭킹 — 판마다 "사람별 점수" 단락 하나와 공통 꼬리({@link #RANK_TAIL})를 이어 붙인 쿼리.
 *
 * <p>판을 한 쿼리에 몰아 CASE 로 고르지 않는 이유: 서로 다른 주·지역·빵집 세기(COUNT DISTINCT)가 사람마다 정렬을
 * 부르는 무거운 일이라, 안 볼 판의 점수까지 세면 낭비다. 판별 단락은 자기 점수·동점 기준·도달 시각만 센다.
 *
 * <p>모든 단락이 내는 칸: {@code member_id, score, tie, reached_at}.
 * 순위는 점수 → 동점 기준 → 먼저 도달(그 점수를 만든 마지막 기록의 작성 시각) → 회원 id 순이라 공동 순위가 없다.
 */
public interface BreadRecordRankingRepository extends Repository<BreadRecord, Long> {

	/**
	 * 빵집 식별 — 가게가 없으면 이름(공백 없애고 소문자)으로. BreadRecordRepository.findStoreVisits 와 같다.
	 * 붙이는 쪽에서 괄호로 감싼다 — 텍스트 블록은 줄 끝 공백을 지워 "DISTINCT CASE" 가 "DISTINCTCASE" 로 붙는다.
	 */
	String STORE_KEY = """
			CASE WHEN r.store_id IS NOT NULL THEN 'id:' || r.store_id
				ELSE 'name:' || LOWER(REGEXP_REPLACE(r.store_name, '[[:space:]]+', '', 'g')) END""";

	/**
	 * 빵로그왕 — 비공개 포함 빵로그 수. :weekStart 를 주면 그 뒤에 쓴(created_at) 기록만(이번 주 빵로그왕),
	 * :sido·:sigungu 를 주면 그 시·군·구 기록만(내 지역 — region_key 첫·둘째 칸이 둘 다 같아야 한다). 동점은 다녀간 빵집 수.
	 *
	 * <p>이번 주를 먹은 날(eaten_at)이 아니라 쓴 날로 자르는 이유: 지난 빵을 이번 주 날짜로 적어 몰아 올릴 수 없게.
	 */
	String RECORDS_SCORED = """
			WITH scored AS (
				SELECT r.member_id,
						COUNT(*) AS score,
						COUNT(DISTINCT (""" + STORE_KEY + """
			)) AS tie,
						MAX(r.created_at) AS reached_at
				FROM bread_record r
				WHERE r.deleted_at IS NULL
					AND (CAST(:weekStart AS TIMESTAMP) IS NULL OR r.created_at >= CAST(:weekStart AS TIMESTAMP))
					AND (CAST(:sido AS VARCHAR) IS NULL
						OR (split_part(r.region_key, '_', 1) = CAST(:sido AS VARCHAR)
							AND split_part(r.region_key, '_', 2) = CAST(:sigungu AS VARCHAR)))
				GROUP BY r.member_id
			)""";

	/** 전국 정복왕 — 비공개 포함 빵로그를 남긴 동네(region_key = 시도_구_동 전체) 수. 동점은 다녀간 빵집 수. */
	String REGIONS_SCORED = """
			WITH scored AS (
				SELECT r.member_id,
						COUNT(DISTINCT r.region_key) AS score,
						COUNT(DISTINCT ("""
		+ STORE_KEY + """
				)) AS tie,
							MAX(r.created_at) AS reached_at
					FROM bread_record r
					WHERE r.deleted_at IS NULL AND r.region_key IS NOT NULL
					GROUP BY r.member_id
				)""";

	/** 공유왕 — 공개한 빵로그 수. 동점은 전체(비공개 포함) 빵로그 수. */
	String SHARE_SCORED = """
			WITH scored AS (
				SELECT r.member_id,
						COUNT(*) FILTER (WHERE r.is_public) AS score,
						COUNT(*) AS tie,
						MAX(r.created_at) FILTER (WHERE r.is_public) AS reached_at
				FROM bread_record r
				WHERE r.deleted_at IS NULL
				GROUP BY r.member_id
			)""";

	/**
	 * 공통 꼬리 — 탈퇴·게스트·블로그 봇·보는 사람이 차단한 사람을 빼고 번호를 매긴 뒤,
	 * 상위 :lim 명 + 나 + 내 바로 위 사람(내가 :lim 밖이어도 다음 목표를 계산하려고)만 돌려준다.
	 */
	String RANK_TAIL = """
			,
			numbered AS (
				SELECT s.member_id, m.nickname, mw.selected_character, s.score,
						ROW_NUMBER() OVER (ORDER BY s.score DESC, s.tie DESC, s.reached_at ASC, s.member_id ASC) AS rank
				FROM scored s
				JOIN member m ON m.id = s.member_id
				LEFT JOIN member_world mw ON mw.member_id = s.member_id
				WHERE s.score > 0
					AND m.deleted_at IS NULL
					AND m.member_type = 'USER'
					AND (CAST(:viewer AS BIGINT) IS NULL OR NOT EXISTS (
						SELECT 1 FROM member_block mb
						WHERE mb.blocker_id = CAST(:viewer AS BIGINT) AND mb.blocked_member_id = s.member_id))
			),
			with_first AS (
				SELECT numbered.*, MIN(rank) OVER (PARTITION BY score) AS score_first_rank
				FROM numbered
			)
			SELECT member_id AS memberId, nickname, selected_character AS selectedCharacter,
					score, rank, score_first_rank AS scoreFirstRank
			FROM with_first
			WHERE rank <= :lim
				OR member_id = CAST(:viewer AS BIGINT)
				OR rank = (SELECT rank - 1 FROM with_first WHERE member_id = CAST(:viewer AS BIGINT))
			ORDER BY rank
			""";

	@Query(value = RECORDS_SCORED + RANK_TAIL, nativeQuery = true)
	List<RankingRowProjection> findRecordRanking(
			@Param("weekStart") LocalDateTime weekStart,
			@Param("sido") String sido,
			@Param("sigungu") String sigungu,
			@Param("viewer") Long viewerId,
			@Param("lim") int limit);

	@Query(value = REGIONS_SCORED + RANK_TAIL, nativeQuery = true)
	List<RankingRowProjection> findRegionRanking(
			@Param("viewer") Long viewerId,
			@Param("lim") int limit);

	@Query(value = SHARE_SCORED + RANK_TAIL, nativeQuery = true)
	List<RankingRowProjection> findShareRanking(
			@Param("viewer") Long viewerId,
			@Param("lim") int limit);
}
