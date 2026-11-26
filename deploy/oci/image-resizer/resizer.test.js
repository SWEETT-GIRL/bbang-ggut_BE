"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const sharp = require("sharp");
const { processObject, isResizeTarget } = require("./resizer");

const silent = { log() {}, error() {} };

function fakeStorage(objects) {
  const stored = new Map();
  return {
    stored,
    async getObject(name) {
      return objects.has(name) ? objects.get(name) : null;
    },
    async putObject(name, body, options) {
      stored.set(name, { body, options });
    }
  };
}

function fakeWebhook({ failWith } = {}) {
  const calls = [];
  return {
    calls,
    async send(awsKey, status, failReason) {
      calls.push({ awsKey, status, failReason });
      if (failWith) {
        throw failWith;
      }
    }
  };
}

function jpeg(width, height) {
  return sharp({ create: { width, height, channels: 3, background: { r: 200, g: 120, b: 40 } } })
    .jpeg()
    .toBuffer();
}

test("리사이즈 대상 프리픽스만 처리한다", () => {
  assert.equal(isResizeTarget("stores/1/a"), true);
  assert.equal(isResizeTarget("bread-records/9/a"), true);
  assert.equal(isResizeTarget("resized/320/stores/1/a"), false);
  assert.equal(isResizeTarget("world/planet.png"), false);
  assert.equal(isResizeTarget("banners/a"), false);
  assert.equal(isResizeTarget("onboarding/tutorials/a.jpg"), false);
});

test("이미지를 세 크기 webp 로 만들고 DONE 을 알린다", async () => {
  const key = "stores/1/photo";
  const storage = fakeStorage(new Map([[key, await jpeg(2000, 1500)]]));
  const webhook = fakeWebhook();

  const result = await processObject(key, storage, webhook, silent);

  assert.equal(result, "DONE");
  assert.deepEqual([...storage.stored.keys()], [
    "resized/320/stores/1/photo",
    "resized/640/stores/1/photo",
    "resized/1024/stores/1/photo"
  ]);
  for (const [name, { body, options }] of storage.stored) {
    const meta = await sharp(body).metadata();
    const width = Number(name.split("/")[1]);
    assert.equal(meta.format, "webp");
    assert.equal(meta.width, width);
    assert.equal(meta.height, Math.round((width * 1500) / 2000));
    assert.equal(options.contentType, "image/webp");
    assert.match(options.cacheControl, /immutable/);
  }
  assert.deepEqual(webhook.calls, [{ awsKey: key, status: "DONE", failReason: null }]);
});

test("원본보다 큰 크기로는 늘리지 않는다", async () => {
  const key = "menus/1/small";
  const storage = fakeStorage(new Map([[key, await jpeg(400, 300)]]));

  await processObject(key, storage, fakeWebhook(), silent);

  const widths = [];
  for (const { body } of storage.stored.values()) {
    widths.push((await sharp(body).metadata()).width);
  }
  assert.deepEqual(widths, [320, 400, 400]);
});

test("대상이 아닌 프리픽스는 읽지도 쓰지도 알리지도 않는다", async () => {
  const key = "world/planet.png";
  const storage = fakeStorage(new Map([[key, await jpeg(100, 100)]]));
  const webhook = fakeWebhook();

  const result = await processObject(key, storage, webhook, silent);

  assert.equal(result, "SKIPPED");
  assert.equal(storage.stored.size, 0);
  assert.equal(webhook.calls.length, 0);
});

test("자기가 만든 resized/ 객체에는 다시 반응하지 않는다", async () => {
  const storage = fakeStorage(new Map());
  const result = await processObject("resized/320/stores/1/photo", storage, fakeWebhook(), silent);
  assert.equal(result, "SKIPPED");
});

test("원본이 사라졌으면 콜백 없이 넘어간다", async () => {
  const webhook = fakeWebhook();
  const result = await processObject("stores/1/gone", fakeStorage(new Map()), webhook, silent);
  assert.equal(result, "SKIPPED");
  assert.equal(webhook.calls.length, 0);
});

test("이미지가 아니면 FAILED 를 알리고 정상 종료한다", async () => {
  const key = "reviews/1/not-image";
  const storage = fakeStorage(new Map([[key, Buffer.from("<html>not an image</html>")]]));
  const webhook = fakeWebhook();

  const result = await processObject(key, storage, webhook, silent);

  assert.equal(result, "FAILED");
  assert.equal(storage.stored.size, 0);
  assert.equal(webhook.calls[0].status, "FAILED");
  assert.match(webhook.calls[0].failReason, /^INVALID_FILE_TYPE/);
});

test("GIF 는 리사이즈하지 않고 DONE 만 알린다", async () => {
  const key = "stores/1/anim";
  const gif = await sharp({ create: { width: 10, height: 10, channels: 3, background: "red" } }).gif().toBuffer();
  const storage = fakeStorage(new Map([[key, gif]]));
  const webhook = fakeWebhook();

  const result = await processObject(key, storage, webhook, silent);

  assert.equal(result, "DONE");
  assert.equal(storage.stored.size, 0);
  assert.deepEqual(webhook.calls, [{ awsKey: key, status: "DONE", failReason: null }]);
});

test("헤더만 이미지고 내용이 깨졌으면 FAILED 를 알린다", async () => {
  const key = "menus/1/broken";
  const broken = (await jpeg(800, 600)).subarray(0, 40);
  const storage = fakeStorage(new Map([[key, broken]]));
  const webhook = fakeWebhook();

  const result = await processObject(key, storage, webhook, silent);

  assert.equal(result, "FAILED");
  assert.match(webhook.calls.at(-1).failReason, /^RESIZE_FAILED/);
});

test("DONE 통보가 실패하면 throw 해서 다시 전달받는다(FAILED 로 뒤집지 않는다)", async () => {
  const key = "stores/1/photo";
  const storage = fakeStorage(new Map([[key, await jpeg(800, 600)]]));
  const webhook = fakeWebhook({ failWith: new Error("Webhook failed: 503") });

  await assert.rejects(() => processObject(key, storage, webhook, silent), /Webhook failed: 503/);

  assert.equal(storage.stored.size, 3);
  assert.deepEqual(webhook.calls.map((call) => call.status), ["DONE"]);
});

test("FAILED 통보가 닿지 못하면 throw 해서 다시 전달받는다 — 삼키면 BE 가 실패를 모른다", async () => {
  const key = "reviews/1/not-image";
  const storage = fakeStorage(new Map([[key, Buffer.from("<html>not an image</html>")]]));
  const webhook = fakeWebhook({ failWith: new Error("Webhook failed: 503") });

  await assert.rejects(() => processObject(key, storage, webhook, silent), /Webhook failed: 503/);

  assert.deepEqual(webhook.calls.map((call) => call.status), ["FAILED"]);
});

test("리사이즈 실패의 FAILED 통보가 닿지 못해도 throw 한다", async () => {
  const key = "stores/1/broken";
  const png = await sharp({ create: { width: 10, height: 10, channels: 3, background: "#fff" } }).png().toBuffer();
  const storage = fakeStorage(new Map([[key, png.subarray(0, 24)]]));
  const webhook = fakeWebhook({ failWith: new Error("Webhook failed: 503") });

  await assert.rejects(() => processObject(key, storage, webhook, silent), /Webhook failed: 503/);
});

test("FAILED 통보가 거부되면(4xx) 다시 전달받지 않고 정상 종료한다", async () => {
  const key = "reviews/1/not-image";
  const storage = fakeStorage(new Map([[key, Buffer.from("<html>not an image</html>")]]));
  const rejected = Object.assign(new Error("Webhook rejected: 401"), { rejected: true });
  const webhook = fakeWebhook({ failWith: rejected });

  const result = await processObject(key, storage, webhook, silent);

  assert.equal(result, "FAILED");
});

test("저장 실패는 일시적 실패로 올린다", async () => {
  const key = "stores/1/photo";
  const storage = fakeStorage(new Map([[key, await jpeg(800, 600)]]));
  storage.putObject = async () => {
    throw new Error("storage 503");
  };
  const webhook = fakeWebhook();

  await assert.rejects(() => processObject(key, storage, webhook, silent), /storage 503/);
  assert.equal(webhook.calls.length, 0);
});
