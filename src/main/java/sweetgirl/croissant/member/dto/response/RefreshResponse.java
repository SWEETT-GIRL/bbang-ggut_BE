package sweetgirl.croissant.member.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RefreshResponse {

	// 새로 발급된 Access Token
	// 클라이언트는 이 값을 이후 API 요청의 Authorization: Bearer {accessToken} 헤더에 사용
	private String accessToken;
}
