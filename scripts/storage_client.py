"""운영 스크립트가 쓰는 이미지 저장소(S3 호환) 클라이언트.

BE 의 S3Config 와 같은 설정을 읽는다 — application-local.properties 의 storage.*.
storage.endpoint 가 있으면 그 주소(OCI Object Storage 등)로, 없으면 AWS S3 로 붙는다.
주소를 무시하면 OCI 의 키로 AWS 에 접속하게 되어, DB 는 지워졌는데 객체만 남는다.
"""
import boto3
from botocore.config import Config


def s3_client(cfg: dict):
	"""cfg: load_props() 가 읽은 application-local.properties 의 key → value."""
	endpoint = (cfg.get("storage.endpoint") or "").strip()
	path_style = (cfg.get("storage.path-style-access") or "").strip().lower() == "true"

	options = {
		"region_name": cfg["storage.region"],
		"aws_access_key_id": cfg["storage.credentials.access-key"],
		"aws_secret_access_key": cfg["storage.credentials.secret-key"],
	}
	if endpoint:
		options["endpoint_url"] = endpoint
		options["config"] = _compatible_config(path_style)
	return boto3.client("s3", **options)


def _compatible_config(path_style: bool) -> Config:
	addressing = {"addressing_style": "path" if path_style else "auto"}
	try:
		# botocore 1.36 부터 요청마다 체크섬(CRC32)을 붙이는데, S3 호환 스토리지는 이를 거부할 수 있다.
		# BE(S3Config)와 같이 "필요할 때만"으로 낮춘다.
		return Config(
			s3=addressing,
			request_checksum_calculation="when_required",
			response_checksum_validation="when_required",
		)
	except TypeError:
		# 그 전 버전은 이 옵션이 없고, 체크섬도 기본으로 붙이지 않는다.
		return Config(s3=addressing)
