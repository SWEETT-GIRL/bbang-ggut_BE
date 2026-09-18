package sweetgirl.croissant.breadlog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.external.aws.S3Service;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:bread-record-repository-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class BreadRecordRepositoryTest {

	private static final long STORE_ID = 1L;
	private static final long OTHER_STORE_ID = 2L;
	private static final long VIEWER_ID = 1L;
	private static final long AUTHOR_ID = 2L;
	private static final long BLOCKED_AUTHOR_ID = 3L;
	private static final long WITHDRAWN_AUTHOR_ID = 4L;

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private BreadRecordRepository breadRecordRepository;

	@Autowired
	private EntityManager entityManager;

	@BeforeEach
	void setUp() {
		execute("DROP TABLE IF EXISTS member_block CASCADE");
		execute("DROP TABLE IF EXISTS bread_record_loved_bread CASCADE");
		execute("DROP TABLE IF EXISTS bread_record CASCADE");
		execute("DROP TABLE IF EXISTS store CASCADE");
		execute("DROP TABLE IF EXISTS member CASCADE");
		execute("""
				CREATE TABLE member (
					id BIGINT PRIMARY KEY,
					nickname VARCHAR(100) NOT NULL,
					pending_nickname VARCHAR(100),
					nickname_drawn_at TIMESTAMP,
					deleted_at TIMESTAMP,
					agree_to_terms BOOLEAN NOT NULL DEFAULT TRUE,
					agree_to_marketing BOOLEAN NOT NULL DEFAULT FALSE,
					agree_to_night_marketing BOOLEAN NOT NULL DEFAULT FALSE,
					agree_to_privacy BOOLEAN NOT NULL DEFAULT FALSE,
					agree_to_age_over14 BOOLEAN NOT NULL DEFAULT FALSE,
					agree_to_location BOOLEAN NOT NULL DEFAULT FALSE,
					terms_version VARCHAR(20),
					privacy_version VARCHAR(20),
					terms_agreed_at TIMESTAMP,
					marketing_agreed_at TIMESTAMP,
					member_type VARCHAR(20) NOT NULL DEFAULT 'USER',
					created_at TIMESTAMP NOT NULL,
					updated_at TIMESTAMP
				)
				""");
		execute("""
				CREATE TABLE store (
					id BIGINT PRIMARY KEY,
					name VARCHAR(100) NOT NULL,
					address VARCHAR(255) NOT NULL,
					region_1st VARCHAR(50),
					region_2nd VARCHAR(50),
					region_3rd VARCHAR(50),
					location VARCHAR(255),
					phone VARCHAR(20),
					status VARCHAR(50) NOT NULL,
					kakao_place_id VARCHAR(50),
					naver_place_id VARCHAR(50),
					naver_matched_at TIMESTAMP,
					naver_enrich_attempts INTEGER NOT NULL DEFAULT 0,
					source VARCHAR(50) NOT NULL,
					category VARCHAR(20),
					created_at TIMESTAMP NOT NULL,
					updated_at TIMESTAMP
				)
				""");
		execute("""
				CREATE TABLE bread_record (
					id BIGINT PRIMARY KEY,
					member_id BIGINT NOT NULL,
					store_id BIGINT,
					store_name VARCHAR(100) NOT NULL,
					region_key VARCHAR(100),
					memo VARCHAR(500),
					loved_bread VARCHAR(100),
					eaten_at TIMESTAMP NOT NULL,
					deleted_at TIMESTAMP,
					is_public BOOLEAN NOT NULL DEFAULT FALSE,
					created_at TIMESTAMP NOT NULL,
					updated_at TIMESTAMP
				)
				""");
		execute("""
				CREATE TABLE bread_record_loved_bread (
					record_id BIGINT NOT NULL,
					sort_order INTEGER NOT NULL,
					name VARCHAR(100) NOT NULL
				)
				""");
		execute("""
				CREATE TABLE member_block (
					id BIGINT PRIMARY KEY,
					blocker_id BIGINT NOT NULL,
					blocked_member_id BIGINT NOT NULL,
					created_at TIMESTAMP NOT NULL
				)
				""");

		saveMember(VIEWER_ID, "보는 사람");
		saveMember(AUTHOR_ID, "작성자");
		saveMember(BLOCKED_AUTHOR_ID, "차단된 작성자");
		saveMember(WITHDRAWN_AUTHOR_ID, "탈퇴한 작성자");
		execute("UPDATE member SET deleted_at = TIMESTAMP '2026-09-01 00:00:00' WHERE id = ?", WITHDRAWN_AUTHOR_ID);
		saveStore(STORE_ID, "단팥당");
		saveStore(OTHER_STORE_ID, "밀도");
		execute("""
				INSERT INTO member_block (id, blocker_id, blocked_member_id, created_at)
				VALUES (1, ?, ?, TIMESTAMP '2026-09-01 00:00:00')
				""", VIEWER_ID, BLOCKED_AUTHOR_ID);
	}

	@Test
	@DisplayName("가게의 공개 기록만 먹은 날 최신순으로 — 비공개·삭제·다른 가게·탈퇴한 작성자는 빠진다")
	void findPublicByStoreId_onlyPublicActiveRecordsOfStore_latestEatenFirst() {
		saveRecord(1L, AUTHOR_ID, STORE_ID, true, "2026-09-01 10:00:00", false);
		saveRecord(2L, AUTHOR_ID, STORE_ID, true, "2026-09-03 10:00:00", false);
		saveRecord(3L, AUTHOR_ID, STORE_ID, false, "2026-09-04 10:00:00", false);
		saveRecord(4L, AUTHOR_ID, STORE_ID, true, "2026-09-05 10:00:00", true);
		saveRecord(5L, AUTHOR_ID, OTHER_STORE_ID, true, "2026-09-06 10:00:00", false);
		saveRecord(6L, WITHDRAWN_AUTHOR_ID, STORE_ID, true, "2026-09-07 10:00:00", false);

		List<BreadRecord> result = breadRecordRepository.findPublicByStoreId(STORE_ID, null, PageRequest.of(0, 10));

		assertThat(result).extracting(BreadRecord::getId).containsExactly(2L, 1L);
	}

	@Test
	@DisplayName("로그인한 사람이 차단한 작성자의 공개 기록은 빠지고, 비로그인은 그대로 본다")
	void findPublicByStoreId_excludesBlockedAuthorForViewerOnly() {
		saveRecord(1L, AUTHOR_ID, STORE_ID, true, "2026-09-01 10:00:00", false);
		saveRecord(2L, BLOCKED_AUTHOR_ID, STORE_ID, true, "2026-09-02 10:00:00", false);

		assertThat(breadRecordRepository.findPublicByStoreId(STORE_ID, VIEWER_ID, PageRequest.of(0, 10)))
			.extracting(BreadRecord::getId)
			.containsExactly(1L);
		assertThat(breadRecordRepository.findPublicByStoreId(STORE_ID, null, PageRequest.of(0, 10)))
			.extracting(BreadRecord::getId)
			.containsExactly(2L, 1L);
	}

	private void saveMember(Long id, String nickname) {
		execute("""
				INSERT INTO member (id, nickname, created_at)
				VALUES (?, ?, TIMESTAMP '2026-09-01 00:00:00')
				""", id, nickname);
	}

	private void saveStore(Long id, String name) {
		execute("""
				INSERT INTO store (id, name, address, status, source, naver_enrich_attempts, created_at)
				VALUES (?, ?, '서울 마포구', 'ACTIVE', 'ADMIN_REGISTERED', 0, TIMESTAMP '2026-09-01 00:00:00')
				""", id, name);
	}

	private void saveRecord(Long id, Long memberId, Long storeId, boolean isPublic, String eatenAt, boolean deleted) {
		execute(
			"""
						INSERT INTO bread_record (
						id, member_id, store_id, store_name, eaten_at, is_public, deleted_at, created_at
					)
						VALUES (?, ?, ?, '가게', CAST(? AS TIMESTAMP), ?,
							CASE WHEN ? THEN TIMESTAMP '2026-09-10 00:00:00' END, TIMESTAMP '2026-09-01 00:00:00')
						""",
			id, memberId, storeId, eatenAt, isPublic, deleted);
	}

	private void execute(String sql, Object... parameters) {
		var query = entityManager.createNativeQuery(sql);
		for (int i = 0; i < parameters.length; i++) {
			query.setParameter(i + 1, parameters[i]);
		}
		query.executeUpdate();
	}
}
