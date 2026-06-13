package sweetgirl.croissant.external.opensearch.config;

import java.time.Duration;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import sweetgirl.croissant.external.aws.config.AwsProperties;

@Configuration
@Profile({"staging", "prod"})
@ConditionalOnProperty(name = "opensearch.endpoint")
@RequiredArgsConstructor
public class OpenSearchConfig {

	private final OpenSearchProperties openSearchProperties;
	private final AwsProperties awsProperties;

	@Bean
	public OpenSearchClient openSearchClient() {
		AwsBasicCredentials credentials = AwsBasicCredentials.create(
			awsProperties.getCredentials().getAccessKey(),
			awsProperties.getCredentials().getSecretKey());
		SdkHttpClient httpClient = ApacheHttpClient.builder()
			.socketTimeout(Duration.ofSeconds(120))
			.connectionTimeout(Duration.ofSeconds(5))
			.build();

		return new OpenSearchClient(
			new AwsSdk2Transport(
				httpClient,
				openSearchProperties.getEndpoint(),
				"es",
				Region.of(resolveRegion()),
				AwsSdk2TransportOptions.builder()
					.setCredentials(StaticCredentialsProvider.create(credentials))
					.build()));
	}

	private String resolveRegion() {
		String region = openSearchProperties.getRegion();
		return (region != null && !region.isBlank()) ? region : awsProperties.getRegion();
	}
}
