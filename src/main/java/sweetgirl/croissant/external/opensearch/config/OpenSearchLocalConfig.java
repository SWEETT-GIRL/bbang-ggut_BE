package sweetgirl.croissant.external.opensearch.config;

import java.net.URISyntaxException;

import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local")
@ConditionalOnProperty(name = "opensearch.endpoint")
public class OpenSearchLocalConfig {

	@Value("${opensearch.endpoint}")
	private String endpoint;

	@Bean
	public OpenSearchClient openSearchClient() throws URISyntaxException {
		HttpHost host = HttpHost.create(endpoint);
		OpenSearchTransport transport = ApacheHttpClient5TransportBuilder.builder(host).build();
		return new OpenSearchClient(transport);
	}
}
