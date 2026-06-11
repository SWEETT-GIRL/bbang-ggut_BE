package sweetgirl.croissant.external.opensearch.config;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;

@Configuration
@ConditionalOnProperty(name = "opensearch.endpoint")
public class OpenSearchConfig {

	@Value("${opensearch.endpoint}")
	private String endpoint;

	@Value("${aws.region}")
	private String region;

	@Value("${aws.credentials.access-key}")
	private String accessKey;

	@Value("${aws.credentials.secret-key}")
	private String secretKey;

	@Bean
	public OpenSearchClient openSearchClient() {
		AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
		SdkHttpClient httpClient = ApacheHttpClient.builder().build();

		return new OpenSearchClient(
			new AwsSdk2Transport(
				httpClient,
				endpoint,
				"es",
				Region.of(region),
				AwsSdk2TransportOptions.builder()
					.setCredentials(StaticCredentialsProvider.create(credentials))
					.build()));
	}
}
