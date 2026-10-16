package sweetgirl.croissant.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import sweetgirl.croissant.admin.entity.AdminAuditLog;
import sweetgirl.croissant.external.aws.S3Service;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:admin-audit-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class AdminAuditLogRepositoryTest {

	private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private AdminAuditLogRepository repository;

	@Test
	@DisplayName("대상으로 거르고 최신순으로 준다. 필터가 비면 전부")
	void search_filtersByTargetNewestFirst() {
		save("MEMBER", "1", NOW.minus(2, ChronoUnit.HOURS));
		AdminAuditLog newer = save("MEMBER", "1", NOW.minus(1, ChronoUnit.HOURS));
		save("COURSE", "9", NOW);
		PageRequest newestFirst = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "id"));

		assertThat(repository.search("MEMBER", "1", newestFirst).getContent())
			.hasSize(2).first().extracting(AdminAuditLog::getId).isEqualTo(newer.getId());
		assertThat(repository.search("COURSE", null, newestFirst).getTotalElements()).isEqualTo(1);
		assertThat(repository.search(null, null, newestFirst).getTotalElements()).isEqualTo(3);
	}

	@Test
	@DisplayName("보관 기한 전 줄만 지운다")
	void deleteCreatedBefore_removesOnlyExpired() {
		save("MEMBER", "1", NOW.minus(181, ChronoUnit.DAYS));
		save("MEMBER", "2", NOW.minus(179, ChronoUnit.DAYS));

		int deleted = repository.deleteCreatedBefore(NOW.minus(180, ChronoUnit.DAYS));

		assertThat(deleted).isEqualTo(1);
		assertThat(repository.findAll()).extracting(AdminAuditLog::getTargetId).containsExactly("2");
	}

	private AdminAuditLog save(String targetType, String targetId, Instant createdAt) {
		return repository.save(AdminAuditLog.builder()
			.method("POST").path("/api/admin/x").targetType(targetType).targetId(targetId).statusCode(200)
			.createdAt(createdAt).build());
	}
}
