package sweetgirl.croissant.personalization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.dto.request.BbtiQuizAnswer;
import sweetgirl.croissant.personalization.dto.response.BbtiResultResponse;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.InteractionType;
import sweetgirl.croissant.personalization.enums.QuizAxis;
import sweetgirl.croissant.personalization.enums.QuizChoice;
import sweetgirl.croissant.personalization.enums.TasteProfileRefreshOutcome;
import sweetgirl.croissant.personalization.enums.TasteTag;
import sweetgirl.croissant.personalization.enums.TasteTypeSource;
import sweetgirl.croissant.personalization.enums.TasteUserType;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.personalization.repository.UserTasteSignalJdbcRepository;

/**
 * 랩 PG(운영 덤프 + dataops 가게 프로필 40,248행)에서 읽기 SQL · jsonb 태그 파싱 · 종류 조인 · 빈 행 INSERT → FOR UPDATE 가
 * 실제로 도는지. 신호가 없는 실제 회원 하나를 골라 신호를 넣고 되돌린다(테스트 트랜잭션 롤백). 전체 재계산(recomputeAll)은
 * 회원별 REQUIRES_NEW 가 테스트 트랜잭션 밖에서 커밋되므로 여기서 부르지 않는다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, PersonalizationProperties.class, UserTasteSignalJdbcRepository.class,
	TasteProfileCalculator.class, UserTasteProfileCommandService.class, TasteQuizCommandService.class})
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
class UserTasteProfileCommandServiceLabTest {

	@Autowired
	private UserTasteProfileCommandService profileCommandService;
	@Autowired
	private TasteQuizCommandService quizCommandService;
	@Autowired
	private UserTasteSignalJdbcRepository signalRepository;
	@Autowired
	private UserTasteProfileRepository profileRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EntityManager entityManager;

	private Long memberId;
	private List<Long> stores;

	@BeforeEach
	void pickFixtures() {
		memberId = jdbcTemplate.queryForObject("""
				SELECT m.id FROM member m
				WHERE NOT EXISTS (SELECT 1 FROM user_store_interaction i WHERE i.member_id = m.id)
					AND NOT EXISTS (SELECT 1 FROM user_taste_profile p WHERE p.member_id = m.id)
					AND NOT EXISTS (SELECT 1 FROM course_store_visits v WHERE v.member_id = m.id)
				ORDER BY m.id
				LIMIT 1
				""", Long.class);
		stores = jdbcTemplate.queryForList("""
				SELECT store_id FROM store_profile
				WHERE axis_taste IS NOT NULL AND tags <> '{}'::jsonb
				ORDER BY store_id
				LIMIT 2
				""", Long.class);
		assertThat(stores).hasSize(2);
	}

	private void insertSignal(InteractionType type, Long storeId, Long menuId, Boolean converted,
			LocalDateTime occurredAt) {
		jdbcTemplate.update("""
				INSERT INTO user_store_interaction (member_id, store_id, menu_id, type, context, converted, occurred_at)
				VALUES (?, ?, ?, ?, NULL, ?, ?)
				""", memberId, storeId, menuId, type.name(), converted, occurredAt);
	}

	private BigDecimal storeAxisTaste(Long storeId) {
		return jdbcTemplate.queryForObject("SELECT axis_taste FROM store_profile WHERE store_id = ?", BigDecimal.class,
			storeId);
	}

	private UserTasteProfile reload() {
		entityManager.flush();
		entityManager.clear();
		return profileRepository.findById(memberId).orElseThrow();
	}

	@Test
	@DisplayName("저장·담기 두 가게의 축·태그를 상속한다 — 축은 두 가게 축 사이, 태그는 12키, 출처 SIGNAL, version 2")
	void recompute_inheritsStoreAxisAndTags() {
		LocalDateTime now = LocalDateTime.now();
		insertSignal(InteractionType.SAVE, stores.get(0), null, null, now.minusDays(1));
		insertSignal(InteractionType.COURSE_ADD, stores.get(1), null, null, now.minusDays(2));

		TasteProfileRefreshOutcome outcome = profileCommandService.recompute(memberId);

		assertThat(outcome).isEqualTo(TasteProfileRefreshOutcome.RECOMPUTED);
		UserTasteProfile profile = reload();
		assertThat(profile.hasAxis()).isTrue();
		assertThat(profile.getTypeSource()).isEqualTo(TasteTypeSource.SIGNAL);
		assertThat(profile.getTypeCode()).hasSize(4);
		assertThat(profile.getTags()).containsOnlyKeys(
			java.util.Arrays.stream(TasteTag.values()).map(TasteTag::getKey).toArray(String[]::new));
		assertThat(profile.getStrongStoreCount()).isEqualTo(2);
		assertThat(profile.getBreadSignalCount()).isZero();
		assertThat(profile.getUserType()).isEqualTo(TasteUserType.BROWSER);
		assertThat(profile.getVersion()).isEqualTo(2);
		BigDecimal first = storeAxisTaste(stores.get(0));
		BigDecimal second = storeAxisTaste(stores.get(1));
		assertThat(profile.getAxis().getTaste()).isBetween(first.min(second).subtract(new BigDecimal("0.001")),
			first.max(second).add(new BigDecimal("0.001")));
	}

	@Test
	@DisplayName("같은 신호로 다시 계산하면 UNCHANGED 고 version 이 그대로다 — 매일 배치가 재사용 키를 깨지 않는다")
	void recompute_twice_isUnchanged() {
		insertSignal(InteractionType.SAVE, stores.get(0), null, null, LocalDateTime.now().minusDays(1));
		profileCommandService.recompute(memberId);
		entityManager.flush();

		TasteProfileRefreshOutcome second = profileCommandService.recompute(memberId);

		assertThat(second).isEqualTo(TasteProfileRefreshOutcome.UNCHANGED);
		assertThat(reload().getVersion()).isEqualTo(2);
	}

	@Test
	@DisplayName("검사 제출: 빈 행 INSERT → FOR UPDATE 뒤 검사 ×3 과 저장 가게를 합산해 프로필과 응답이 같은 타입이다")
	void quizSubmit_locksRowAndMergesSignals() {
		insertSignal(InteractionType.SAVE, stores.get(0), null, null, LocalDateTime.now().minusDays(1));
		List<BbtiQuizAnswer> allB = List.of(new BbtiQuizAnswer(QuizAxis.TASTE, QuizChoice.B),
			new BbtiQuizAnswer(QuizAxis.TEXTURE, QuizChoice.B), new BbtiQuizAnswer(QuizAxis.FILLING, QuizChoice.B),
			new BbtiQuizAnswer(QuizAxis.VIBE, QuizChoice.B));

		BbtiResultResponse response = quizCommandService.submit(memberId, allB);

		UserTasteProfile profile = reload();
		assertThat(profile.hasTakenQuiz()).isTrue();
		assertThat(profile.getTypeSource()).isEqualTo(TasteTypeSource.SIGNAL);
		assertThat(profile.getVersion()).isEqualTo(3); // 빈 행 1 → 검사 2 → 합산 3
		assertThat(response.typeCode()).isEqualTo(profile.getTypeCode());
		BigDecimal storeTaste = storeAxisTaste(stores.get(0));
		assertThat(profile.getAxis().getTaste()).isBetween(storeTaste.min(BigDecimal.ONE), BigDecimal.ONE);
	}

	@Test
	@DisplayName("기록 빵의 종류는 menu → menu_category.name 으로 온다 — 가게 kinds 와 같은 어휘")
	void recordBread_kindComesFromMenuCategory() {
		Map<String, Object> menu = jdbcTemplate.queryForMap("""
				SELECT m.id AS menu_id, m.store_id, mc.name AS kind
				FROM menu m JOIN menu_category mc ON mc.id = m.menu_category_id
				WHERE m.deleted_at IS NULL
				ORDER BY m.id
				LIMIT 1
				""");
		insertSignal(InteractionType.RECORD_BREAD, (Long)menu.get("store_id"), (Long)menu.get("menu_id"), null,
			LocalDateTime.now().minusDays(1));

		profileCommandService.recompute(memberId);

		UserTasteProfile profile = reload();
		assertThat(profile.getTopKinds()).containsExactly((String)menu.get("kind"));
		assertThat(profile.getBreadSignalCount()).isEqualTo(1);
		assertThat(profile.getStrongStoreCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("재계산 대상 목록은 창 안 신호 회원과 프로필 회원의 합집합이다")
	void memberIdsToRecompute_unionOfSignalledAndProfiled() {
		insertSignal(InteractionType.SAVE, stores.get(0), null, null, LocalDateTime.now().minusDays(1));
		Long profiledOnly = jdbcTemplate.queryForObject("""
				SELECT m.id FROM member m
				WHERE m.id <> ? AND NOT EXISTS (SELECT 1 FROM user_taste_profile p WHERE p.member_id = m.id)
				ORDER BY m.id
				LIMIT 1
				""", Long.class, memberId);
		profileRepository.insertEmptyIfAbsent(profiledOnly, LocalDateTime.now());

		List<Long> memberIds = signalRepository.findMemberIdsToRecompute(LocalDateTime.now().minusDays(365));

		assertThat(memberIds).contains(memberId, profiledOnly);
	}
}
