package sweetgirl.croissant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import sweetgirl.croissant.breadranking.service.BreadCategorySeeder;
import sweetgirl.croissant.config.SchedulerConfig;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.config.S3Config;

@ActiveProfiles("v2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
	"DB_URL=jdbc:h2:mem:v2-smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"DB_USERNAME=sa", "DB_PASSWORD=test",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"KAKAO_CLIENT_ID=test", "KAKAO_CLIENT_SECRET=test",
	"NAVER_LOGIN_CLIENT_ID=test", "NAVER_LOGIN_CLIENT_SECRET=test",
	"KAKAO_API_KEY=test", "GOOGLE_PLACES_API_KEY=test",
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
	@DisplayName("실제 v2 프로필로 앱이 기동되고 S3 빈을 만들며 자동 데이터 작업은 만들지 않는다")
	void startsWithV2ProfileAndRuntimeValues() {
		assertThat(context.getBeansOfType(S3Config.class)).hasSize(1);
		assertThat(context.getBeansOfType(S3Service.class)).hasSize(1);
		assertThat(context.getBeansOfType(SchedulerConfig.class)).isEmpty();
		assertThat(context.getBeansOfType(BreadCategorySeeder.class)).isEmpty();
	}
}
