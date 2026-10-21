package sweetgirl.croissant.external.kakao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kakao.api")
public class KakaoProperties {

	private String baseUrl;
	private String key;
}
