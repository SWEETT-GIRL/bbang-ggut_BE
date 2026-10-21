package sweetgirl.croissant.store.dto.projection;

public interface StoreImageThumbnailProjection {
	Long getStoreId();

	String getAwsKey();

	String getResizedKey320();
}
