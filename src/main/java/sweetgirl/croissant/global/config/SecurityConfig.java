package sweetgirl.croissant.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

		// CSRF 비활성화
		http.csrf(csrf -> csrf.disable());

		// 세션 정책 - STATELESS
		http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

		// 요청별 접근 권한 설정
		http.authorizeHttpRequests(auth -> auth
			.requestMatchers("/oauth2/**").permitAll()
			.requestMatchers("/login/**").permitAll()
			.requestMatchers("/api/v1/oauth2/**").permitAll()
			.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
			.anyRequest().authenticated());

		// OAuth2 로그인 설정
		http.oauth2Login(oauth2 -> oauth2
			.redirectionEndpoint(redirection -> redirection.baseUri("/api/v1/oauth2/*")));

		return http.build();
	}
}
