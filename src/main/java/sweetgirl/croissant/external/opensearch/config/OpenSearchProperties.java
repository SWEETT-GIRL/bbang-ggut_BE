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
	// 검색 점수 산출 내역(_explanation) 요청 여부 — 디버그 전용. 운영 기본은 false, 로컬에서만 true로 켠다.
	private boolean explainEnabled = false;
}
