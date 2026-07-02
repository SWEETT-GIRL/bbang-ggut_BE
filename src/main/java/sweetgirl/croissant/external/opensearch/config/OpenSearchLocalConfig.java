package sweetgirl.croissant.external.opensearch.config;

import java.net.URISyntaxException;

import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import lombok.RequiredArgsConstructor;

@Configuration
@Profile("local")
@ConditionalOnProperty(name = "opensearch.endpoint")
@RequiredArgsConstructor
public class OpenSearchLocalConfig {

	private final OpenSearchProperties openSearchProperties;

	@Bean
	public OpenSearchClient openSearchClient() throws URISyntaxException {
		HttpHost host = HttpHost.create(openSearchProperties.getEndpoint());
		OpenSearchTransport transport = ApacheHttpClient5TransportBuilder.builder(host).build();
		return new OpenSearchClient(transport);
	}
}
