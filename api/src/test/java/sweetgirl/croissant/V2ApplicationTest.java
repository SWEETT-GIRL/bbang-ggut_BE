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
import org.springframework.test.context.ActiveProfiles;

import sweetgirl.croissant.breadranking.service.BreadCategorySeeder;
import sweetgirl.croissant.config.SchedulerConfig;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.config.S3Config;

@ActiveProfiles("v2")
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
	"DB_URL=jdbc:h2:mem:v2-smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"DB_USERNAME=sa", "DB_PASSWORD=test",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	// 마이그레이션은 PostgreSQL 전용이라 H2 로 띄우는 이 테스트에서만 끈다
	"spring.flyway.enabled=false",
	"KAKAO_CLIENT_ID=test", "KAKAO_CLIENT_SECRET=test",
	"NAVER_LOGIN_CLIENT_ID=test", "NAVER_LOGIN_CLIENT_SECRET=test",
	"KAKAO_API_KEY=test",
	"JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"ENCRYPT_SECRET_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"AWS_S3_BUCKET=test-bucket", "LAMBDA_CALLBACK_SECRET_KEY=test", "ADMIN_API_KEY=test-admin-key",
	"aws.credentials.access-key=test", "aws.credentials.secret-key=test",
	"firebase.config.path=firebase/firebase-service-account.json"
})
class V2ApplicationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("Google Places 키 없이 v2 앱이 기동되고 S3 빈을 만들며 자동 데이터 작업은 만들지 않는다")
	void startsWithV2ProfileAndRuntimeValues() {
		assertThat(context.getBeansOfType(S3Config.class)).hasSize(1);
		assertThat(context.getBeansOfType(S3Service.class)).hasSize(1);
		assertThat(context.getBeansOfType(SchedulerConfig.class)).isEmpty();
		assertThat(context.getBeansOfType(BreadCategorySeeder.class)).isEmpty();
	}

	@Test
	@DisplayName("v2의 앱 오류와 예외 스택이 콘솔에 출력된다")
	void writesApplicationErrorsToConsole(CapturedOutput output) {

		LoggerFactory.getLogger(V2ApplicationTest.class)
			.error("v2-console-regression-check", new IllegalStateException("v2-startup-error-example"));

		assertThat(output.getOut()).contains(
			"v2-console-regression-check",
			"java.lang.IllegalStateException: v2-startup-error-example");
	}
}
