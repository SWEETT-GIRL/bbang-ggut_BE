package sweetgirl.croissant.external.aws.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "aws")
public class AwsProperties {

	private String region;
	private final Credentials credentials = new Credentials();

	@Getter
	@Setter
	public static class Credentials {

		private String accessKey;
		private String secretKey;
	}
}
