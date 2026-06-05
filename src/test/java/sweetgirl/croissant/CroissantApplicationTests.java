package sweetgirl.croissant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.firebase.config.FirebaseConfig;

@SpringBootTest(properties = {
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"posthog.api-key=test",
	"oauth2.front-redirect-uri=http://localhost",
	"jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"jwt.access-token-expiry=3600000",
	"jwt.refresh-token-expiry=1209600000",
	"google.places.api-key=test",
	"encrypt.secret-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class CroissantApplicationTests {

	@MockitoBean
	S3Service s3Service;

	@MockitoBean
	FirebaseConfig firebaseConfig;

	@MockitoBean
	ClientRegistrationRepository clientRegistrationRepository;

	@Test
	void contextLoads() {
		assertThat(true).isTrue();
	}

}
