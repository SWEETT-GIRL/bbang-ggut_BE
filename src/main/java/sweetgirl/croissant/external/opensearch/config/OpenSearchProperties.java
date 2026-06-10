package sweetgirl.croissant.external.opensearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "opensearch")
public class OpenSearchProperties {

	private String baseUrl = "http://localhost:9200";

	private String storesIndex = "stores";
}
