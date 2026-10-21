package sweetgirl.croissant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.context.WebApplicationContext;

import sweetgirl.croissant.batch.job.BatchJobRegistry;
import sweetgirl.croissant.batch.job.BatchJobRunner;
import sweetgirl.croissant.breadranking.service.BreadCategorySeeder;

/**
 * 배포용 application.properties 그대로(테스트용 설정 파일 없이) 배치 앱이 기동되는지 본다.
 * 여기 넘기는 값이 곧 Parameter Store 에 있어야 하는 필수 값이다.
 */
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
	"DB_URL=jdbc:h2:mem:batch-smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"DB_USERNAME=sa", "DB_PASSWORD=test",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"shedlock.enabled=false",
	"ENCRYPT_SECRET_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"AWS_S3_BUCKET=test-bucket",
	"aws.credentials.access-key=test", "aws.credentials.secret-key=test",
	"firebase.config.path=firebase/firebase-service-account.json"
})
class BatchApplicationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("웹 서버 없이 기동되고, 작업 이름이 없으면 아무 작업도 실행하지 않는다")
	void startsWithoutWebServerOrJob() {
		assertThat(context).isNotInstanceOf(WebApplicationContext.class);
		assertThat(context.getBeansOfType(BatchJobRunner.class)).isEmpty();
	}

	@Test
	@DisplayName("기동할 때마다 도는 데이터 보정(시더)은 만들지 않는다")
	void doesNotRunStartupDataMaintenance() {
		assertThat(context.getBeansOfType(BreadCategorySeeder.class)).isEmpty();
	}

	@Test
	@DisplayName("EventBridge 규칙이 쓰는 작업 이름이 전부 등록된다")
	void registersAllJobs() {
		assertThat(context.getBean(BatchJobRegistry.class).jobs().keySet()).containsExactlyInAnyOrder(
			"admin-audit-log-retention",
			"course-reminder",
			"monthly-report-run-due",
			"ranking-reward-issue",
			"ranking-snapshot",
			"reward-attendance-reminder",
			"reward-letter-arrival",
			"reward-second-morning-roulette",
			"search-miss-retention",
			"search-miss-triage",
			"store-popularity-score",
			"store-trending-baseline",
			"user-interaction-ingest",
			"withdrawn-member-retention");
	}

	@Test
	@DisplayName("batch 프로필의 로그와 예외 스택이 콘솔에 찍힌다 — 실패 원인을 SSM 실행 결과에서 본다")
	void writesLogsToConsole(CapturedOutput output) {
		LoggerFactory.getLogger(BatchApplicationTest.class)
			.error("batch-console-check", new IllegalStateException("batch-error-example"));

		assertThat(output.getOut()).contains(
			"batch-console-check",
			"java.lang.IllegalStateException: batch-error-example");
	}
}
