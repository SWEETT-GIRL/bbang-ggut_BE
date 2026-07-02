package sweetgirl.croissant.contribution.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.enums.ContributionReportStatus;
import sweetgirl.croissant.contribution.enums.ContributionReportType;
import sweetgirl.croissant.contribution.enums.ContributionTargetType;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:contribution-report-repository-test;"
		+ "MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class ContributionReportRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private ContributionReportRepository contributionReportRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void saveAndFindById_persistsContributionReport() {
		Member member = persistMember();
		ContributionReport report = ContributionReport.builder()
			.member(member)
			.targetType(ContributionTargetType.STORE)
			.reportType(ContributionReportType.ADD)
			.build();

		ContributionReport saved = contributionReportRepository.save(report);
		entityManager.flush();
		entityManager.clear();

		ContributionReport found = contributionReportRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getMember().getId()).isEqualTo(member.getId());
		assertThat(found.getTargetType()).isEqualTo(ContributionTargetType.STORE);
		assertThat(found.getReportType()).isEqualTo(ContributionReportType.ADD);
		assertThat(found.getStatus()).isEqualTo(ContributionReportStatus.PENDING);
		assertThat(found.getReviewedAt()).isNull();
		assertThat(found.getCreatedAt()).isNotNull();
		assertThat(found.getUpdatedAt()).isNotNull();
		assertThat(contributionReportRepository.countByStatus(ContributionReportStatus.PENDING)).isEqualTo(1L);
	}

	@Test
	void approve_updatesStatusAndReviewedAt() {
		ContributionReport report = contributionReportRepository.save(ContributionReport.builder()
			.targetType(ContributionTargetType.MENU)
			.reportType(ContributionReportType.UPDATE)
			.build());

		report.approve();
		entityManager.flush();
		entityManager.clear();

		ContributionReport found = contributionReportRepository.findById(report.getId()).orElseThrow();
		assertThat(found.getStatus()).isEqualTo(ContributionReportStatus.ACCEPTED);
		assertThat(found.getReviewedAt()).isNotNull();
	}

	private Member persistMember() {
		Member member = Member.builder()
			.nickname("제보자")
			.build();
		entityManager.persist(member);
		return member;
	}
}
