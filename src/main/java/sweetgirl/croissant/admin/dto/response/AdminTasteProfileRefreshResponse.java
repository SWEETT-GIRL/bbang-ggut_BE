package sweetgirl.croissant.admin.dto.response;

import sweetgirl.croissant.personalization.dto.response.TasteProfileRefreshResult;

/** 취향 프로필 재계산 수동 실행 결과. recomputed 만 version 이 올랐다. */
public record AdminTasteProfileRefreshResponse(int members, int recomputed, int statsOnly, int unchanged, int failed) {
	public static AdminTasteProfileRefreshResponse of(TasteProfileRefreshResult result) {
		return new AdminTasteProfileRefreshResponse(result.members(), result.recomputed(), result.statsOnly(),
			result.unchanged(), result.failed());
	}
}
