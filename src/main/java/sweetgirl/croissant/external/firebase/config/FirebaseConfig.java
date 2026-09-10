package sweetgirl.croissant.external.firebase.config;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import jakarta.annotation.PostConstruct;

@Configuration
public class FirebaseConfig {

	@Value("${firebase.config.path}")
	private String firebaseConfigPath;

	@PostConstruct
	public void initialize() throws IOException {
		if (FirebaseApp.getApps().isEmpty()) {
			InputStream serviceAccount = new ClassPathResource(firebaseConfigPath).getInputStream();

			FirebaseOptions options = FirebaseOptions.builder()
				.setCredentials(GoogleCredentials.fromStream(serviceAccount))
				.build();

			FirebaseApp.initializeApp(options);
		}
	}

	/**
	 * 발송기를 빈으로 꺼내둔다. 쓰는 쪽이 {@code FirebaseMessaging.getInstance()}를 직접 부르면
	 * 그 클래스는 테스트에서 대역을 끼울 수 없다 — 발송 성패에 따라 갈리는 분기가 전부
	 * 실제 FCM 없이는 확인 불가가 된다.
	 *
	 * <p>{@code @PostConstruct}가 먼저 돌아 FirebaseApp이 준비된 뒤에 이 메서드가 불린다.
	 */
	@Bean
	public FirebaseMessaging firebaseMessaging() {
		return FirebaseMessaging.getInstance();
	}
}
