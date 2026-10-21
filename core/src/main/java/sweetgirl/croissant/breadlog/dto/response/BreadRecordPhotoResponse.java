package sweetgirl.croissant.breadlog.dto.response;

import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;

public record BreadRecordPhotoResponse(
		Long id,
		String fileName,
		String contentType,
		String awsKey,
		String resizedKey320,
		String resizedKey640,
		String resizedKey1024,
		int sortOrder,
		Integer width,
		Integer height) {

	public static BreadRecordPhotoResponse from(BreadRecordPhoto photo) {
		return new BreadRecordPhotoResponse(
			photo.getId(),
			photo.getName(),
			photo.getMimeType().getValue(),
			photo.getAwsKey(),
			photo.getResizedKey320(),
			photo.getResizedKey640(),
			photo.getResizedKey1024(),
			photo.getSortOrder(),
			photo.getWidth(),
			photo.getHeight());
	}
}
