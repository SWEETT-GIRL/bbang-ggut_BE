"use strict";

/**
 * 이미지 리사이즈 본체. AWS Lambda(bbanggut-prod-image-resizer)의 로직을 옮긴 것이다.
 * 저장소와 콜백을 밖에서 주입받는다 — OCI 배선(func.js)과 떼어 두어야 로컬에서 테스트할 수 있다.
 *
 * 실패를 둘로 나눈다.
 *  - 결정적 실패(이미지가 아님, 디코딩 불가, 원본이 사라짐): 다시 해도 같다. BE 에 FAILED 를 알리고 정상 종료한다.
 *  - 일시적 실패(저장소·콜백 오류): throw 한다 → Events 가 다시 전달한다. 처리는 멱등이라 다시 돌아도 무해하다.
 */

const sharp = require("sharp");

// 함수 하나가 이미지 하나를 처리한다. libvips 의 캐시와 스레드 풀은 메모리만 먹는다.
// (Lambda 1GB 에서 2.6~9MB 원본이 OOM 으로 죽었다 — 2026-11-26)
sharp.cache(false);
sharp.concurrency(1);

const SIZES = [320, 640, 1024];

const ALLOWED_MIME_TYPES = ["image/jpeg", "image/png", "image/webp", "image/avif"];

// BE 는 GIF 를 "DONE 이되 resized_key 는 비움"으로 다룬다(ImageProcessingCallbackService.applyStatus).
// 여기서 FAILED 를 보내면 그 분기가 죽은 코드가 된다.
const SKIP_RESIZE_MIME_TYPES = ["image/gif"];

// 리사이즈 대상 프리픽스. BE 콜백(ImageCallbackService.validateSupportedPrefix)이 받는 목록과 같아야 한다.
// 여기에 없는 프리픽스(world/ · banners/ · letters/ · onboarding/ · monthly-reports/ · resized/)는 건드리지 않는다.
// 금지 목록이 아니라 허용 목록으로 둔 이유: 새 프리픽스가 생겼을 때 조용히 webp 로 구워지는 쪽보다
// 리사이즈가 안 되는 쪽이 안전하다(world/ 의 투명 PNG 가 구워져 배경이 날아간 적이 있다).
const RESIZE_PREFIXES = [
  "stores/",
  "menus/",
  "reports/",
  "reviews/",
  "courses/",
  "bread-records/",
  "menu-add-reports/"
];

// 네이버 원천 이미지 중 상당수가 JPEG 종료 마커 없이 저장돼 있다. 기본값(failOn:'warning')은 이를 전부 거부한다.
const SHARP_OPTIONS = {
  failOn: "none",
  limitInputPixels: 500_000_000
};

class PermanentFailure extends Error {
  constructor(message) {
    super(message);
    this.name = "PermanentFailure";
  }
}

function isResizeTarget(objectName) {
  return RESIZE_PREFIXES.some((prefix) => objectName.startsWith(prefix));
}

/**
 * @param {{ getObject(name: string): Promise<Buffer|null>, putObject(name: string, body: Buffer, options: object): Promise<void> }} storage
 *        getObject 는 객체가 없으면 null 을 준다.
 * @param {{ send(awsKey: string, status: "DONE"|"FAILED", failReason: string|null): Promise<void> }} webhook
 * @returns {Promise<"SKIPPED"|"DONE"|"FAILED">}
 */
async function processObject(objectName, storage, webhook, log = console) {
  if (!isResizeTarget(objectName)) {
    return "SKIPPED";
  }

  const imageBuffer = await storage.getObject(objectName);
  if (imageBuffer === null) {
    // 업로드 직후 지워진 객체(크롤 중복 등). 리사이즈할 대상이 사라진 것이라 실패가 아니다.
    // 콜백을 보내면 짝 없는 콜백이 BE 대기열에 쌓인다.
    log.log(`Object no longer exists, skip: ${objectName}`);
    return "SKIPPED";
  }

  const mimeType = await detectMimeType(imageBuffer);

  if (mimeType && SKIP_RESIZE_MIME_TYPES.includes(mimeType)) {
    await webhook.send(objectName, "DONE", null);
    log.log(`Resize skipped by policy (${mimeType}): ${objectName}`);
    return "DONE";
  }

  if (!mimeType || !ALLOWED_MIME_TYPES.includes(mimeType)) {
    await reportFailure(webhook, objectName, `INVALID_FILE_TYPE: ${mimeType ?? "unknown"}`, log);
    return "FAILED";
  }

  try {
    await createResizedImages(imageBuffer, objectName, storage);
  } catch (error) {
    if (error instanceof PermanentFailure) {
      await reportFailure(webhook, objectName, error.message, log);
      return "FAILED";
    }
    throw error;
  }

  // DONE 통보는 리사이즈 try 블록 밖에 둔다. 안에 두면 통보 실패가 리사이즈 실패로 뒤집혀
  // 실물이 멀쩡한 이미지가 FAILED 로 바뀐다. 여기서 throw 되면 일시적 실패로 다시 전달된다.
  await webhook.send(objectName, "DONE", null);
  log.log(`Image resized: ${objectName}`);
  return "DONE";
}

async function detectMimeType(buffer) {
  // file-type 은 ESM 전용이라 동적으로 불러온다.
  const { fileTypeFromBuffer } = await import("file-type");
  const fileInfo = await fileTypeFromBuffer(buffer);
  return fileInfo?.mime;
}

async function createResizedImages(imageBuffer, objectName, storage) {
  for (const width of SIZES) {
    let processedBuffer;
    try {
      processedBuffer = await sharp(imageBuffer, SHARP_OPTIONS)
        .rotate()
        .resize({
          width,
          height: width,
          fit: sharp.fit.inside,
          withoutEnlargement: true
        })
        .webp({ quality: 80 })
        .toBuffer();
    } catch (error) {
      throw new PermanentFailure(`RESIZE_FAILED(${width}): ${error.message}`);
    }

    // 저장 실패는 일시적일 수 있으므로 그대로 전파한다.
    await storage.putObject(`resized/${width}/${objectName}`, processedBuffer, {
      contentType: "image/webp",
      cacheControl: "public, max-age=31536000, immutable"
    });
  }
}

/** FAILED 통보. 통보 자체가 실패해도 결정적 실패의 흐름을 막지 않는다 — 다시 해봐야 같은 실패가 반복된다. */
async function reportFailure(webhook, objectName, failReason, log) {
  log.error(`Image processing failed: ${objectName} — ${failReason}`);
  try {
    await webhook.send(objectName, "FAILED", failReason);
  } catch (error) {
    log.error(`Could not report FAILED for ${objectName}: ${error.message}`);
  }
}

module.exports = { processObject, isResizeTarget, SIZES, RESIZE_PREFIXES };
