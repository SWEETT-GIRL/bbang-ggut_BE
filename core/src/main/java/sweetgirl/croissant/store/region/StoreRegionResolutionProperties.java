package sweetgirl.croissant.store.region;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "store.region-resolution")
public class StoreRegionResolutionProperties {
	/** 경계 적재·검수 후 명시적으로 활성화한다. */
	private boolean enabled = false;
	private String versionKey = "";
	private int batchSize = 500;
}
