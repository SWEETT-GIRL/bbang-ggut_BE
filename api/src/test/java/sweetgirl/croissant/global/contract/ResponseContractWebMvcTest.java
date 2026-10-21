package sweetgirl.croissant.global.contract;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 응답 계약 테스트(*ResponseContractTest)가 상속하는 슬라이스. 앱이 읽는 응답 JSON 모양만 보므로 보안은 뺀다.
 *
 * <p>standaloneSetup 이 아니라 @WebMvcTest 를 쓰는 이유: 실제 응답은 Boot 가 자동 구성한 Jackson(3) 으로 직렬화된다.
 * JacksonConfig 의 ObjectMapper(Jackson 2) 로 직접 직렬화하면 boolean 키 이름·시간 형식이 실제 응답과 달라질 수 있다.
 * 하위 클래스에서 @ContextConfiguration 으로 대상 컨트롤러를 지정하고 서비스는 @MockitoBean 으로 둔다.
 */
@WebMvcTest(excludeAutoConfiguration = {SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class,
	SecurityFilterAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
	OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
public abstract class ResponseContractWebMvcTest {

	@Autowired
	protected MockMvc mockMvc;
}
