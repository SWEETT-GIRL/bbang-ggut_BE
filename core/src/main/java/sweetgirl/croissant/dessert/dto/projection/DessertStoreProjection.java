package sweetgirl.croissant.dessert.dto.projection;

import java.time.LocalDate;

/**
 * 이 디저트를 파는 곳 한 줄. 빵긋 가게에서 오든 편의점 지점에서 오든 같은 모양으로 답한다 —
 * 화면이 타입으로 갈라지지 않게 하려는 것이다.
 */
public interface DessertStoreProjection {

	/** 빵긋 가게 id. 편의점 지점은 우리 가게가 아니라 null 이다 */
	Long getStoreId();

	String getName();

	String getAddress();

	double getLatitude();

	double getLongitude();

	double getDistanceMeters();

	/** 편의점·브랜드 지점은 근거가 아니라 브랜드로 아는 것이라 null 이다 */
	String getGrade();

	Integer getEvidenceCount();

	LocalDate getLastEvidenceAt();
}
