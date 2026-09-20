import { flushPromises, mount } from "@vue/test-utils";
import { defineComponent, h, ref } from "vue";

import { useFinitePolling } from "../src/composables/useFinitePolling";

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void;
  const promise = new Promise<void>(done => { resolve = done; });
  return { promise, resolve };
}

afterEach(() => {
  vi.useRealTimers();
});

describe("useFinitePolling", () => {
  it("waits for one request before scheduling the next and stops at a terminal key", async () => {
    vi.useFakeTimers();
    const key = ref<string | null>("review:1");
    const first = deferred();
    const poll = vi.fn()
      .mockImplementationOnce(() => first.promise)
      .mockImplementationOnce(async () => { key.value = null; });
    const wrapper = mount(defineComponent({
      setup() {
        useFinitePolling(key, poll, { intervalMs: 100 });
        return () => h("div");
      },
    }));
    await flushPromises();

    await vi.advanceTimersByTimeAsync(100);
    expect(poll).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(poll).toHaveBeenCalledTimes(1);

    first.resolve();
    await flushPromises();
    await vi.advanceTimersByTimeAsync(100);
    expect(poll).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(poll).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it("bounds automatic failures, supports manual retry, and cancels on unmount", async () => {
    vi.useFakeTimers();
    const key = ref<string | null>("document:1");
    let failing = true;
    const poll = vi.fn(async () => {
      if (failing) throw new Error("offline");
      key.value = null;
    });
    const wrapper = mount(defineComponent({
      setup() {
        return useFinitePolling(key, poll, {
          intervalMs: 100,
          maxConsecutiveFailures: 3,
        });
      },
      render: () => h("div"),
    }));
    await flushPromises();

    await vi.advanceTimersByTimeAsync(300);
    expect(poll).toHaveBeenCalledTimes(3);
    expect(wrapper.vm.error).toBeInstanceOf(Error);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(poll).toHaveBeenCalledTimes(3);

    failing = false;
    wrapper.vm.retry();
    await vi.advanceTimersByTimeAsync(0);
    expect(poll).toHaveBeenCalledTimes(4);
    expect(wrapper.vm.error).toBeNull();

    key.value = "document:2";
    await flushPromises();
    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(poll).toHaveBeenCalledTimes(4);
  });
});
