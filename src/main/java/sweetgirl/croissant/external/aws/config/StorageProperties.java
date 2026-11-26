package sweetgirl.croissant.external.aws.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

	private String region;

	/**
	 * S3 호환 스토리지의 주소. 비워 두면 AWS S3 기본 주소를 쓴다.
	 * OCI Object Storage 는 {@code https://<namespace>.compat.objectstorage.<region>.oraclecloud.com} 형태다.
	 */
	private String endpoint;

	/** 버킷을 호스트가 아니라 경로에 두는 방식({@code /bucket/key}). S3 호환 스토리지는 대개 이 방식만 받는다. */
	private boolean pathStyleAccess;

	private final Credentials credentials = new Credentials();

	public boolean hasEndpoint() {
		return endpoint != null && !endpoint.isBlank();
	}

	@Getter
	@Setter
	public static class Credentials {

		private String accessKey;
		private String secretKey;
	}
}
