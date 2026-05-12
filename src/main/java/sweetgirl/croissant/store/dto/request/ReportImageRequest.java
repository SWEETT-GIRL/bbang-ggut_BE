package sweetgirl.croissant.store.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ReportImageRequest {
	private String fileName;
	private String contentType;
	private String awsKey;

}
