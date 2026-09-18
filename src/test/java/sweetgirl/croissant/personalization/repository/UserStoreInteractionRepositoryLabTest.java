package sweetgirl.croissant.personalization.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

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

import sweetgirl.croissant.config.persistence.JpaConfig;

/** 게스트 → 회원 병합의 신호 이관(#663 ④): NULLS NOT DISTINCT 유니크와 겹치는 게스트 행은 지우고 나머지만 옮긴다 — 랩 PG 에서만 검증 가능. */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
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
class UserStoreInteractionRepositoryLabTest {

	private static final LocalDateTime AT = LocalDateTime.of(2020, 1, 1, 10, 0);

	@Autowired
	private UserStoreInteractionRepository repository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long guestId;
	private Long targetId;

	@BeforeEach
	void pickMembers() {
		List<Long> members = jdbcTemplate.queryForList("""
				SELECT m.id FROM member m
				WHERE NOT EXISTS (SELECT 1 FROM user_store_interaction i WHERE i.member_id = m.id)
				ORDER BY m.id
				LIMIT 2
				""", Long.class);
		assertThat(members).hasSize(2);
		guestId = members.get(0);
		targetId = members.get(1);
	}

	private void insert(Long memberId, String type, Long storeId, Long menuId, LocalDateTime occurredAt) {
		jdbcTemplate.update("""
				INSERT INTO user_store_interaction (member_id, store_id, menu_id, type, occurred_at)
				VALUES (?, ?, ?, ?, ?)
				""", memberId, storeId, menuId, type, occurredAt);
	}

	private int count(Long memberId) {
		Integer count = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM user_store_interaction WHERE member_id = ?", Integer.class, memberId);
		return count != null ? count : 0;
	}

	@Test
	@DisplayName("회원이 이미 가진 사건(store · menu NULL 도 같은 값)은 지우고, 나머지 게스트 행만 회원에게 옮긴다")
	void moveOwner_afterRemovingDuplicates() {
		insert(targetId, "SAVE", 100L, null, AT);
		insert(guestId, "SAVE", 100L, null, AT); // 회원과 같은 사건 — 옮기면 유니크 충돌
		insert(guestId, "SAVE", 100L, null, AT.plusHours(1)); // 시각이 다르니 별개
		insert(guestId, "RECORD_BREAD", 100L, 7L, AT); // menu_id 가 있는 사건
		insert(guestId, "RECO_DISMISSED", 200L, null, AT);

		int removed = repository.deleteGuestDuplicates(guestId, targetId);
		int moved = repository.moveOwner(guestId, targetId);

		assertThat(removed).isEqualTo(1);
		assertThat(moved).isEqualTo(3);
		assertThat(count(guestId)).isZero();
		assertThat(count(targetId)).isEqualTo(4);
	}
}
