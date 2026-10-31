/** 串行执行扫描，仅应用最新请求的结果 */
export const createLatestScanRunner = <T>(
  scan: () => Promise<T>,
  apply: (result: T) => Promise<void>,
  onError: (error: unknown) => void,
) => {
  let revision = 0;
  let queue = Promise.resolve();
  return {
    request() {
      const requested = ++revision;
      queue = queue.then(async () => {
        if (requested !== revision) return;
        try {
          const result = await scan();
          if (requested === revision) await apply(result);
        } catch (error) {
          if (requested === revision) onError(error);
        }
      });
      return queue;
    },
    cancel() {
      revision++;
    },
  };
};
