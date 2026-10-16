package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.world.dto.request.AdminWorldCharacterUpdateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldMemberGrantRequest;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.service.WorldCatalogVersionRaiser;

/**
 * 어드민 세계관 저장이 벌크 쿼리({@code clearAutomatically}) 뒤에도 엔티티 변경을 DB 에 남기는지 실제 트랜잭션으로 본다.
 *
 * 벌크 쿼리는 영속성 컨텍스트를 비운다. 그 전에 읽어 둔 엔티티는 준영속이 되어, 그 뒤의 변경은 예외 없이 조용히 버려진다 —
 * Mockito 단위 테스트는 이것을 잡지 못한다. 저장이 끝난 뒤 jdbc 로 다시 읽어 확인한다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:admin-world-bulk-update-persistence-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminWorldBulkUpdatePersistenceTest {

	private static final Long MEMBER_ID = 7L;

	@MockitoBean
	private S3Service s3Service;

	// 카탈로그 번호 행(world_catalog)은 이 테스트의 관심이 아니다
	@MockitoBean
	private WorldCatalogVersionRaiser worldCatalogVersionRaiser;

	@MockitoSpyBean
	private MemberCharacterRepository memberCharacterRepository;

	@Autowired
	private AdminWorldCharacterService adminWorldCharacterService;

	@Autowired
	private AdminWorldMemberService adminWorldMemberService;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@BeforeEach
	void setUp() {
		transactionTemplate.executeWithoutResult(status -> {
			entityManager.persist(WorldCharacter.create("dog", "빵댕이", null, "earth", 1, true));
			entityManager.persist(WorldCharacter.create("cat", "냥이", null, "earth", 2, false));
			entityManager.persist(MemberWorld.start(MEMBER_ID, "dog"));
		});
		// H2 는 ON CONFLICT DO NOTHING 을 모른다 — 같은 트랜잭션(같은 커넥션)에서 도는 MERGE 로 바꿔 끼운다.
		// 바로 뒤의 awakeIfSleeping 이 진짜 벌크 쿼리라 컨텍스트가 비워지는 조건은 그대로다.
		willAnswer(invocation -> jdbcTemplate.update("""
				MERGE INTO member_characters (member_id, character_code, status, ad_views, acquired_by, acquired_at)
				KEY (member_id, character_code) VALUES (?, ?, 'AWAKE', 0, ?, CURRENT_TIMESTAMP)
				""", invocation.getArguments()))
			.given(memberCharacterRepository).grantIfAbsent(any(), any(), any());
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.update("DELETE FROM member_characters");
		jdbcTemplate.update("DELETE FROM member_world");
		jdbcTemplate.update("DELETE FROM world_characters");
	}

	@Test
	@DisplayName("마스코트를 바꾸면 옛 마스코트는 내려가고 새 마스코트와 함께 보낸 변경이 저장된다 — 내리는 벌크 UPDATE 가 컨텍스트를 비운 뒤라도")
	void update_promotingMascot_persistsChangesAfterBulkUpdate() {
		adminWorldCharacterService.update("cat",
			new AdminWorldCharacterUpdateRequest("새 냥이", null, null, null, true, null));

		assertThat(mascotCodes()).containsExactly("cat");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT name FROM world_characters WHERE code = 'cat'", String.class)).isEqualTo("새 냥이");
	}

	private List<String> mascotCodes() {
		return jdbcTemplate.queryForList(
			"SELECT code FROM world_characters WHERE mascot = TRUE ORDER BY code", String.class);
	}
}
