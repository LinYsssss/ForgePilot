import {
  onBeforeUnmount,
  onMounted,
  shallowRef,
  toValue,
  watch,
  type MaybeRefOrGetter,
} from "vue";

interface PollingOptions {
  intervalMs?: number;
  maxConsecutiveFailures?: number;
}

/**
 * Poll one displayed resource without overlapping requests. A null key means
 * the resource is terminal (or absent), so polling stops until the key changes.
 */
export function useFinitePolling(
  key: MaybeRefOrGetter<string | null>,
  poll: () => Promise<void>,
  options: PollingOptions = {},
) {
  const intervalMs = options.intervalMs ?? 2_000;
  const maxConsecutiveFailures = options.maxConsecutiveFailures ?? 3;
  const error = shallowRef<unknown | null>(null);
  let consecutiveFailures = 0;
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;

  function visible(): boolean {
    return typeof document === "undefined" || document.visibilityState !== "hidden";
  }

  function clearTimer(): void {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  }

  function pause(): void {
    generation++;
    clearTimer();
  }

  function schedule(currentGeneration: number, delay = intervalMs): void {
    if (currentGeneration !== generation || toValue(key) === null || !visible()) return;
    timer = setTimeout(() => { void run(currentGeneration); }, delay);
  }

  async function run(currentGeneration: number): Promise<void> {
    timer = null;
    if (currentGeneration !== generation || toValue(key) === null || !visible()) return;
    try {
      await poll();
      consecutiveFailures = 0;
      error.value = null;
    } catch (failure: unknown) {
      consecutiveFailures++;
      error.value = failure;
    }
    if (currentGeneration === generation && toValue(key) !== null
      && consecutiveFailures < maxConsecutiveFailures) {
      schedule(currentGeneration);
    }
  }

  function restart(immediate = false): void {
    pause();
    consecutiveFailures = 0;
    error.value = null;
    if (toValue(key) !== null) schedule(generation, immediate ? 0 : intervalMs);
  }

  function visibilityChanged(): void {
    if (visible()) restart();
    else pause();
  }

  watch(() => toValue(key), () => restart(), { immediate: true, flush: "post" });
  onMounted(() => document.addEventListener("visibilitychange", visibilityChanged));
  onBeforeUnmount(() => {
    pause();
    document.removeEventListener("visibilitychange", visibilityChanged);
  });

  return { error, retry: () => restart(true) };
}
