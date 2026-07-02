package sweetgirl.croissant.menu.dto.projection;

public interface MenuImageThumbnailProjection {
	Long getImageId();

	Long getMenuId();

	String getAwsKey();

	String getResizedKey320();

	String getResizedKey640();

	String getResizedKey1024();
}
