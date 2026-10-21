package sweetgirl.dataops.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * WAS 와 한 레포가 되면서 Spring Security 가 클래스패스에 들어왔다. 기본 설정(전체 Basic 인증)이 Python 워커를 막지 않도록
 * 세션·폼·Basic 을 끄고, 인증은 {@link DataopsApiKeyFilter} 하나로 한다.
 *
 * <p>웹 서버로 뜰 때만 만든다 — 에이전트용 비웹 러너(AgentManifestApplyMain·ReplayBridgeMain)에는 HttpSecurity 가 없다.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DataopsSecurityConfig {

	@Bean
	public SecurityFilterChain dataopsSecurityFilterChain(HttpSecurity http,
			@Value("${dataops.api-key:}") String apiKey) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
			.addFilterBefore(new DataopsApiKeyFilter(apiKey), UsernamePasswordAuthenticationFilter.class)
			.build();
	}
}
