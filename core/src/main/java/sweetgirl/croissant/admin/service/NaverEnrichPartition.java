package sweetgirl.croissant.admin.service;

import java.util.Locale;

import org.springframework.util.StringUtils;

import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.exception.AdminException;

public enum NaverEnrichPartition {

	P1,
	P2,
	P3;

	public static NaverEnrichPartition fromNullable(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return NaverEnrichPartition.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new AdminException(AdminErrorCode.NAVER_ENRICH_INVALID_PARTITION);
		}
	}

	public static NaverEnrichPartition fromRequired(String value) {
		NaverEnrichPartition partition = fromNullable(value);
		if (partition == null) {
			throw new AdminException(AdminErrorCode.NAVER_ENRICH_INVALID_PARTITION);
		}
		return partition;
	}

	public static String valueOfNullable(NaverEnrichPartition partition) {
		return partition != null ? partition.name() : null;
	}
}
