package sweetgirl.croissant.breadlog.dto.projection;

/** 빵 분류별 태그 수 한 줄. */
public interface BreadCategoryCountProjection {
	String getName();

	Long getTagCount();
}
