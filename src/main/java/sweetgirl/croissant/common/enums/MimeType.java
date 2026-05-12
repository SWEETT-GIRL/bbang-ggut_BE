package sweetgirl.croissant.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;

@Getter
@AllArgsConstructor
public enum MimeType {
	IMAGE_JPEG("image/jpeg"),
	IMAGE_PNG("image/png"),
	IMAGE_WEBP("image/webp"),
	IMAGE_HEIC("image/heic");

	private final String value;

	public static MimeType fromString(String mimeType) {
		for (MimeType type : MimeType.values()) {
			if (type.value.equalsIgnoreCase(mimeType)) {
				return type;
			}
		}
		throw new S3StorageException(S3ErrorCode.INVALID_FILE_TYPE, "허용되지 않는 MIME 타입: " + mimeType);
	}
}
