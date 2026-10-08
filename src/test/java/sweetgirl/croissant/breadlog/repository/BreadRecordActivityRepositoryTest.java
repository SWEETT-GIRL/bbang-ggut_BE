package sweetgirl.croissant.breadlog.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import sweetgirl.croissant.breadlog.dto.projection.BreadRecordActivityProjection;
import sweetgirl.croissant.breadlog.enums.RecordType;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class BreadRecordActivityRepositoryTest {

	@Autowired
	BreadRecordRepository repository;
	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc.execute("""
				CREATE TABLE IF NOT EXISTS bread_record (
					id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL,
					record_type VARCHAR(20) NOT NULL, created_at TIMESTAMP NOT NULL,
					eaten_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP, is_public BOOLEAN NOT NULL)
				""");
	}

	@Test
	void selectsOwnActiveRecordsIncludingPrivateTutorialWithoutJoiningStore() {
		insert(1, 7, "TUTORIAL", false, false, "2026-10-07 23:59:59");
		insert(2, 7, "NORMAL", true, false, "2026-10-08 00:00:00");
		insert(3, 7, "NORMAL", false, true, "2026-10-08 00:00:00");
		insert(4, 8, "NORMAL", false, false, "2026-10-08 00:00:00");
		insert(5, 7, "NORMAL", false, false, "2026-10-09 00:00:00");
		var records = repository.findAllByMemberIdAndDeletedAtIsNullAndCreatedAtLessThanEqual(
			7L, LocalDateTime.parse("2026-10-08T00:00:00"));
		assertThat(records).extracting(BreadRecordActivityProjection::getId).containsExactlyInAnyOrder(1L, 2L);
		assertThat(records).extracting(BreadRecordActivityProjection::getRecordType)
			.containsExactlyInAnyOrder(RecordType.TUTORIAL, RecordType.NORMAL);
		assertThat(records).extracting(BreadRecordActivityProjection::getEatenAt)
			.containsOnly(LocalDateTime.parse("2026-09-01T12:00:00"));
	}

	@Test
	void deletionAndOwnerMigrationAreReflectedWithoutDailyCache() {
		insert(10, 7, "TUTORIAL", false, false, "2026-10-08 00:00:00");
		jdbc.update("UPDATE bread_record SET member_id = 8 WHERE id = 10");
		var now = LocalDateTime.parse("2026-10-08T12:00:00");
		assertThat(repository.findAllByMemberIdAndDeletedAtIsNullAndCreatedAtLessThanEqual(7L, now)).isEmpty();
		assertThat(repository.findAllByMemberIdAndDeletedAtIsNullAndCreatedAtLessThanEqual(8L, now)).hasSize(1);
		jdbc.update("UPDATE bread_record SET deleted_at = ? WHERE id = 10", now);
		assertThat(repository.findAllByMemberIdAndDeletedAtIsNullAndCreatedAtLessThanEqual(8L, now)).isEmpty();
	}

	private void insert(long id, long member, String type, boolean isPublic, boolean deleted, String createdAt) {
		jdbc.update("""
				INSERT INTO bread_record (id, member_id, record_type, created_at, eaten_at, deleted_at, is_public)
				VALUES (?, ?, ?, CAST(? AS TIMESTAMP), TIMESTAMP '2026-09-01 12:00:00',
					CASE WHEN ? THEN TIMESTAMP '2026-10-08 12:00:00' END, ?)
				""", id, member, type, createdAt, deleted, isPublic);
	}
}
