package sweetgirl.croissant.external.aws.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@Profile({"local", "staging", "prod"})
@RequiredArgsConstructor
public class S3Config {

	private final AwsProperties awsProperties;

	private AwsCredentialsProvider credentialsProvider() {
		String accessKey = awsProperties.getCredentials().getAccessKey();
		String secretKey = awsProperties.getCredentials().getSecretKey();
		if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
			return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
		}
		return DefaultCredentialsProvider.create();
	}

	@Bean
	public S3Presigner s3Presigner() {
		return S3Presigner.builder()
			.region(Region.of(awsProperties.getRegion()))
			.credentialsProvider(credentialsProvider())
			.build();
	}

	@Bean
	public S3Client s3Client() {
		return S3Client.builder()
			.overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(15))
				.apiCallAttemptTimeout(Duration.ofSeconds(5)))
			.region(Region.of(awsProperties.getRegion()))
			.credentialsProvider(credentialsProvider())
			.build();
	}
}
