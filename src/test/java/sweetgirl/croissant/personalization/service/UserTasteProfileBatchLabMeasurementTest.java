package sweetgirl.croissant.personalization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.enums.TasteProfileRefreshOutcome;
import sweetgirl.croissant.personalization.repository.UserTasteSignalJdbcRepository;

/**
 * 계측용 랩 테스트: 운영 신호를 읽기 전용 \copy 로 옮겨 둔 scratch_sig(member_id, store_id, t, at)가 랩에 있으면 그 행을
 * user_store_interaction 에 넣고 전 회원을 재계산해 분포와 소요 시간을 로그로 남긴다(테스트 트랜잭션 롤백). 없으면 건너뛴다.
 * 실패 0 과 "강한 신호 회원의 90% 이상이 축을 얻는다"만 단언한다 — 나머지 수치는 PR 본문의 근거다.
 */
@Slf4j
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, PersonalizationProperties.class, UserTasteSignalJdbcRepository.class,
	TasteProfileCalculator.class, UserTasteProfileCommandService.class})
@TestPropertySource(properties = {
	"spring.datasource.url=${lab.db.url:jdbc:postgresql://localhost:5432/soboro}",
	"spring.datasource.username=${lab.db.user:sweetgirl}",
	"spring.datasource.password=${lab.db.password:soboro-lab}",
	"spring.flyway.enabled=true",
	"spring.flyway.out-of-order=true",
	"spring.flyway.baseline-on-migrate=true",
	"spring.flyway.baseline-version=20260604.0",
	"spring.jpa.hibernate.ddl-auto=validate",
	"spring.jpa.show-sql=false"
})
class UserTasteProfileBatchLabMeasurementTest {

	@Autowired
	private UserTasteProfileCommandService profileCommandService;
	@Autowired
	private UserTasteSignalJdbcRepository signalRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("운영 신호 복사본으로 전 회원 재계산 — 실패 0, 강한 신호 회원의 90% 이상이 축을 얻는다 (분포는 로그)")
	void recomputeEveryMember_fromProductionSignals() {
		Boolean scratchExists = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.scratch_sig') IS NOT NULL", Boolean.class);
		Assumptions.assumeTrue(Boolean.TRUE.equals(scratchExists), "랩에 scratch_sig 가 없다 — 운영 신호를 복사한 뒤 돌린다");
		int inserted = jdbcTemplate.update("""
				INSERT INTO user_store_interaction (member_id, store_id, type, occurred_at)
				SELECT member_id, store_id,
					CASE t WHEN 'RECORD' THEN 'RECORD_STORE' WHEN 'VIEW' THEN 'STORE_VIEW' ELSE t END, at
				FROM scratch_sig
				WHERE EXISTS (SELECT 1 FROM member m WHERE m.id = scratch_sig.member_id)
				ON CONFLICT ON CONSTRAINT uq_user_store_interaction DO NOTHING
				""");
		List<Long> memberIds = signalRepository.findMemberIdsToRecompute(LocalDateTime.now().minusDays(365));
		Map<TasteProfileRefreshOutcome, Integer> outcomes = new EnumMap<>(TasteProfileRefreshOutcome.class);
		int failed = 0;
		int processed = 0;
		long startedAt = System.currentTimeMillis();
		for (Long memberId : memberIds) {
			try {
				outcomes.merge(profileCommandService.recompute(memberId), 1, Integer::sum);
			} catch (RuntimeException e) {
				failed++;
				log.warn("[계측] 재계산 실패 memberId={}", memberId, e);
			}
			if (++processed % 100 == 0) {
				entityManager.flush();
				entityManager.clear();
			}
		}
		entityManager.flush();
		entityManager.clear();
		long elapsedMs = System.currentTimeMillis() - startedAt;

		Map<String, Object> stats = jdbcTemplate.queryForMap("""
				SELECT COUNT(*) AS profiles,
					COUNT(*) FILTER (WHERE axis_taste IS NOT NULL) AS with_axis,
					COUNT(*) FILTER (WHERE type_source = 'SIGNAL') AS signal_source,
					COUNT(*) FILTER (WHERE tags <> '{}'::jsonb) AS with_tags,
					COUNT(*) FILTER (WHERE top_kinds IS NOT NULL) AS with_kinds,
					COUNT(*) FILTER (WHERE strong_store_count > 0) AS strong_members,
					COUNT(*) FILTER (WHERE strong_store_count > 0 AND axis_taste IS NOT NULL) AS strong_with_axis,
					COUNT(*) FILTER (WHERE strong_store_count >= 3) AS strong_3plus
				FROM user_taste_profile p
				WHERE EXISTS (SELECT 1 FROM user_store_interaction i WHERE i.member_id = p.member_id)
				""");
		List<Map<String, Object>> userTypes = jdbcTemplate.queryForList("""
				SELECT COALESCE(user_type, '(없음)') AS user_type, COUNT(*) AS n
				FROM user_taste_profile p
				WHERE EXISTS (SELECT 1 FROM user_store_interaction i WHERE i.member_id = p.member_id)
				GROUP BY 1 ORDER BY 2 DESC
				""");
		List<Map<String, Object>> typeCodes = jdbcTemplate.queryForList("""
				SELECT type_code, COUNT(*) AS n FROM user_taste_profile WHERE type_code IS NOT NULL
				GROUP BY 1 ORDER BY 2 DESC
				""");
		List<Map<String, Object>> axisSpread = jdbcTemplate.queryForList("""
				SELECT ROUND(AVG(axis_taste), 3) AS taste_avg, ROUND(STDDEV(axis_taste), 3) AS taste_sd,
					ROUND(AVG(axis_texture), 3) AS texture_avg, ROUND(STDDEV(axis_texture), 3) AS texture_sd,
					ROUND(AVG(axis_filling), 3) AS filling_avg, ROUND(STDDEV(axis_filling), 3) AS filling_sd,
					ROUND(AVG(axis_vibe), 3) AS vibe_avg, ROUND(STDDEV(axis_vibe), 3) AS vibe_sd
				FROM user_taste_profile WHERE axis_taste IS NOT NULL
				""");
		// 테스트 로거는 파일로만 가서 표준 출력에도 남긴다 — 계측 수치는 PR 본문의 근거
		String report = String.format("[계측] 신호 %d행 → 회원 %d명, 결과 %s, 실패 %d, %dms%n[계측] 프로필 %s%n[계측] 유형 %s%n"
			+ "[계측] 타입 %s%n[계측] 축 분포 %s", inserted, memberIds.size(), outcomes, failed, elapsedMs, stats,
			userTypes, typeCodes, axisSpread);
		log.info(report);
		System.out.println(report);

		assertThat(failed).isZero();
		long strongMembers = ((Number)stats.get("strong_members")).longValue();
		long strongWithAxis = ((Number)stats.get("strong_with_axis")).longValue();
		assertThat(strongWithAxis).isGreaterThanOrEqualTo(Math.round(strongMembers * 0.9));
	}
}
