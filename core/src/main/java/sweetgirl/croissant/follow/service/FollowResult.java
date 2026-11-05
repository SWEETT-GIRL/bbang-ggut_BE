package sweetgirl.croissant.follow.service;

/** 팔로우를 눌렀을 때 어떻게 됐나 — 바로 팔로우됐거나(공개 계정), 요청만 남았거나(비공개 계정). */
public enum FollowResult {
	FOLLOWING,
	REQUESTED
}
