package sweetgirl.croissant.external.opensearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "opensearch")
public class OpenSearchProperties {

	private String endpoint;
	private String region;
	private String storesIndex = "stores";
}
