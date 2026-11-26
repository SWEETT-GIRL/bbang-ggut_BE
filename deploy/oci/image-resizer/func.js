"use strict";

/**
 * OCI Functions 진입점. Object Storage 의 객체 생성·덮어쓰기 이벤트를 받아 리사이즈한다.
 *
 *   버킷(객체 이벤트 내보내기) → Events 규칙 → 이 함수 → resized/{320,640,1024}/{key} → BE 콜백
 *
 * 설정(함수 config → 환경변수로 들어온다)
 *   WEBHOOK_URL       BE 콜백 주소. 예: https://bbang-ggut.cloud/callback/images/status
 *   WEBHOOK_SECRET    BE 의 image.callback.secret-key 와 같은 값
 *   WEBHOOK_DISABLED  "true" 면 콜백을 보내지 않는다 — 컷오버 전 리사이즈만 검증할 때 쓴다.
 *                     끄는 스위치를 따로 둔 이유: 주소가 빠졌을 때 조용히 콜백 없이 도는 것을 막기 위해서다.
 *
 * 저장소 인증은 리소스 프린시펄이다 — 키를 설정에 넣지 않는다. 함수가 속한 동적 그룹에
 * 버킷 객체 권한을 주는 정책이 있어야 한다(deploy/oci/image-resizer/README.md).
 */

const fdk = require("@fnproject/fdk");
const common = require("oci-common");
const objectstorage = require("oci-objectstorage");
const { processObject } = require("./resizer");

const HANDLED_EVENT_TYPES = [
  "com.oraclecloud.objectstorage.createobject",
  "com.oraclecloud.objectstorage.updateobject"
];

const WEBHOOK_TIMEOUT_MS = 5_000;
const WEBHOOK_MAX_ATTEMPTS = 3;

const WEBHOOK_DISABLED = process.env.WEBHOOK_DISABLED === "true";
const WEBHOOK_URL = process.env.WEBHOOK_URL || "";
const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET || "";

// 설정 누락은 요청 처리 중간이 아니라 기동에서 드러나야 한다.
if (!WEBHOOK_DISABLED && (!WEBHOOK_URL || !WEBHOOK_SECRET)) {
  throw new Error("WEBHOOK_URL / WEBHOOK_SECRET must be configured (or set WEBHOOK_DISABLED=true)");
}

let storageClient;

function getStorageClient() {
  if (!storageClient) {
    const provider = common.ResourcePrincipalAuthenticationDetailsProvider.builder();
    storageClient = new objectstorage.ObjectStorageClient({ authenticationDetailsProvider: provider });
  }
  return storageClient;
}

function storageFor(namespaceName, bucketName) {
  // 클라이언트는 실제로 읽고 쓸 때 만든다 — 대상이 아닌 객체의 이벤트는 저장소에 닿지 않고 끝난다.
  return {
    async getObject(objectName) {
      let response;
      try {
        response = await getStorageClient().getObject({ namespaceName, bucketName, objectName });
      } catch (error) {
        if (error?.statusCode === 404) {
          return null;
        }
        throw error;
      }
      // SDK 는 Node 버전에 따라 web ReadableStream 이나 Node Readable 을 준다. 둘 다 async iterable 이다.
      const chunks = [];
      for await (const chunk of response.value) {
        chunks.push(Buffer.from(chunk));
      }
      return Buffer.concat(chunks);
    },
    async putObject(objectName, body, { contentType, cacheControl }) {
      await getStorageClient().putObject({
        namespaceName,
        bucketName,
        objectName,
        putObjectBody: body,
        contentLength: body.length,
        contentType,
        cacheControl
      });
    }
  };
}

const webhook = {
  /** 4xx 는 다시 보내도 같으므로 즉시 포기한다(인증 키 오류 등). 그 밖에는 짧게 재시도한다. */
  async send(awsKey, status, failReason) {
    if (WEBHOOK_DISABLED) {
      console.log(`Webhook disabled, not sent: ${awsKey} ${status}`);
      return;
    }

    let lastError;
    for (let attempt = 1; attempt <= WEBHOOK_MAX_ATTEMPTS; attempt++) {
      try {
        const response = await fetch(WEBHOOK_URL, {
          method: "PATCH",
          headers: { "Content-Type": "application/json", "X-Callback-Key": WEBHOOK_SECRET },
          body: JSON.stringify({ awsKey, status, failReason }),
          signal: AbortSignal.timeout(WEBHOOK_TIMEOUT_MS)
        });
        if (response.ok) {
          return;
        }
        const text = await response.text().catch(() => "");
        if (response.status >= 400 && response.status < 500) {
          throw new Error(`Webhook rejected: ${response.status} ${text}`);
        }
        lastError = new Error(`Webhook failed: ${response.status} ${text}`);
      } catch (error) {
        if (error.message?.startsWith("Webhook rejected: ")) {
          throw error;
        }
        lastError = error;
      }
      if (attempt < WEBHOOK_MAX_ATTEMPTS) {
        await new Promise((resolve) => setTimeout(resolve, 200 * 2 ** (attempt - 1)));
      }
    }
    throw lastError;
  }
};

fdk.handle(async (event) => {
  const eventType = event?.eventType;
  const objectName = event?.data?.resourceName;
  const bucketName = event?.data?.additionalDetails?.bucketName;
  const namespaceName = event?.data?.additionalDetails?.namespace;

  if (!HANDLED_EVENT_TYPES.includes(eventType) || !objectName || !bucketName || !namespaceName) {
    console.log(`Ignored event: type=${eventType} object=${objectName}`);
    return { result: "IGNORED" };
  }

  // 일시적 실패는 throw 로 올린다 — FDK 가 502 로 응답하고 Events 가 다시 전달한다.
  const result = await processObject(objectName, storageFor(namespaceName, bucketName), webhook);
  return { result, objectName };
});
