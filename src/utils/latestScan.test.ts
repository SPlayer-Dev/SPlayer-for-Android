import assert from "node:assert/strict";
import test from "node:test";
import { createLatestScanRunner } from "./latestScan";

test("设置变化后丢弃旧扫描，并串行应用最新结果", async () => {
  let finishFirst!: (value: number) => void;
  let calls = 0;
  const applied: number[] = [];
  const runner = createLatestScanRunner(
    async () =>
      ++calls === 1
        ? new Promise<number>((resolve) => {
            finishFirst = resolve;
          })
        : 2,
    async (value) => {
      applied.push(value);
    },
    () => assert.fail("扫描不应失败"),
  );
  const first = runner.request();
  await Promise.resolve();
  const second = runner.request();
  finishFirst(1);
  await Promise.all([first, second]);
  assert.deepEqual(applied, [2]);
});

test("扫描失败不会应用空结果，之后仍可重试", async () => {
  let calls = 0;
  const applied: number[] = [];
  const errors: unknown[] = [];
  const runner = createLatestScanRunner(
    async () => {
      if (++calls === 1) throw new Error("权限已撤销");
      return 3;
    },
    async (value) => {
      applied.push(value);
    },
    (error) => {
      errors.push(error);
    },
  );
  await runner.request();
  await runner.request();
  assert.equal(errors.length, 1);
  assert.deepEqual(applied, [3]);
});

test("离开页面后不应用未完成的扫描", async () => {
  let finish!: (value: number) => void;
  const runner = createLatestScanRunner(
    () =>
      new Promise<number>((resolve) => {
        finish = resolve;
      }),
    async () => assert.fail("已取消的扫描不能更新曲库"),
    () => assert.fail("取消不是扫描错误"),
  );
  const pending = runner.request();
  await Promise.resolve();
  runner.cancel();
  finish(1);
  await pending;
});
