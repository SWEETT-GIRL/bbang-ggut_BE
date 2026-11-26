package sweetgirl.croissant.external.aws.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@Profile({"local", "staging", "prod"})
@RequiredArgsConstructor
public class S3Config {

	private final StorageProperties storageProperties;

	private AwsCredentialsProvider credentialsProvider() {
		String accessKey = storageProperties.getCredentials().getAccessKey();
		String secretKey = storageProperties.getCredentials().getSecretKey();
		if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
			return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
		}
		return DefaultCredentialsProvider.create();
	}

	@Bean
	public S3Presigner s3Presigner() {
		S3Presigner.Builder builder = S3Presigner.builder()
			.region(Region.of(storageProperties.getRegion()))
			.credentialsProvider(credentialsProvider())
			.serviceConfiguration(serviceConfiguration());
		if (storageProperties.hasEndpoint()) {
			builder.endpointOverride(URI.create(storageProperties.getEndpoint()));
		}
		return builder.build();
	}

	@Bean
	public S3Client s3Client() {
		S3ClientBuilder builder = S3Client.builder()
			.overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(15))
				.apiCallAttemptTimeout(Duration.ofSeconds(5)))
			.region(Region.of(storageProperties.getRegion()))
			.credentialsProvider(credentialsProvider())
			.serviceConfiguration(serviceConfiguration());
		if (storageProperties.hasEndpoint()) {
			// SDK 2.30 부터 요청마다 체크섬(CRC32)을 붙이는 게 기본인데, S3 호환 스토리지는 이를 거부할 수 있다.
			// AWS 를 쓸 때의 동작은 바꾸지 않도록 주소를 따로 준 경우에만 "필요할 때만"으로 낮춘다.
			builder.endpointOverride(URI.create(storageProperties.getEndpoint()))
				.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
				.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
		}
		return builder.build();
	}

	private S3Configuration serviceConfiguration() {
		return S3Configuration.builder()
			.pathStyleAccessEnabled(storageProperties.isPathStyleAccess())
			.build();
	}
}
