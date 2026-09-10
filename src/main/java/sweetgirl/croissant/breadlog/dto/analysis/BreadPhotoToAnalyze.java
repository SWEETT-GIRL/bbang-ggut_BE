package sweetgirl.croissant.breadlog.dto.analysis;

/**
 * 분석할 사진 한 장. 저장하지 않고 한 번 보고 버린다.
 *
 * @param photoKey 앱이 사진마다 붙인 키(l0·s12 …). 사진마다 따로 병렬 호출해 완료 순서가 뒤섞이므로
 *                 순번이 아니라 이 키로 결과를 되짚는다
 */
public record BreadPhotoToAnalyze(String photoKey, String mimeType, byte[] image) {
}
