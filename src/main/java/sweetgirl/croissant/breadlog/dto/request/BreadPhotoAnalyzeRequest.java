package sweetgirl.croissant.breadlog.dto.request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.analysis.BreadPhotoToAnalyze;
import sweetgirl.croissant.breadlog.exception.BreadLogException;

/**
 * 분석할 사진 묶음. 사진은 저장하지 않고 한 번 보고 버린다 — 빵로그의 저장 경로는 S3 프리사인드라
 * 따로 있고, 태그 화면 시점에는 사진이 아직 로컬에만 있어 넘길 S3 키가 없다.
 *
 * @param photoKeys 앱이 사진마다 붙인 키. photos 와 개수·순서가 같아야 한다 —
 *                  병렬 호출로 완료 순서가 뒤섞이므로 순번 대신 이 키로 결과를 되짚는다
 */
public record BreadPhotoAnalyzeRequest(

		@NotNull Long storeId,

		@NotEmpty List<String> photoKeys,

		@NotEmpty @Size(max = 30) List<MultipartFile> photos) {

	public List<BreadPhotoToAnalyze> toPhotos() {
		if (photoKeys.size() != photos.size()) {
			throw new BreadLogException(BreadLogErrorCode.PHOTO_KEY_COUNT_MISMATCH);
		}
		List<BreadPhotoToAnalyze> toAnalyze = new ArrayList<>(photos.size());
		for (int index = 0; index < photos.size(); index++) {
			MultipartFile photo = photos.get(index);
			toAnalyze.add(new BreadPhotoToAnalyze(photoKeys.get(index), photo.getContentType(), readBytes(photo)));
		}
		return toAnalyze;
	}

	private static byte[] readBytes(MultipartFile photo) {
		try {
			return photo.getBytes();
		} catch (IOException unreadable) {
			throw new BreadLogException(BreadLogErrorCode.UNREADABLE_PHOTO);
		}
	}
}
