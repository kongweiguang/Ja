// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * jsdom 不实现 ResizeObserver，而 Radix Tooltip 的浮层尺寸计算依赖该浏览器契约；
 * 测试替身只提供生命周期接口，不伪造几何或触发观察回调。
 */
class TestResizeObserver implements ResizeObserver {
  /** 测试不推导布局，注册观察时保持零副作用。 */
  observe(): void {}

  /** 单节点解除与全量清理都不持有资源。 */
  unobserve(): void {}

  /** 测试替身没有后台任务，清理保持幂等。 */
  disconnect(): void {}
}

if (globalThis.ResizeObserver === undefined) {
  globalThis.ResizeObserver = TestResizeObserver;
}

/** jsdom 没有滚动布局；Radix 仍需要该方法完成选中项的无副作用聚焦流程。 */
function testScrollIntoView(): void {}

/** jsdom 不跟踪 pointer capture；返回 false 保留 Radix 在普通点击路径上的分支语义。 */
function testHasPointerCapture(): boolean {
  return false;
}

/** 测试环境只补齐浏览器方法形状，不伪造 pointer capture 生命周期。 */
function testPointerCapture(): void {}

if (HTMLElement.prototype.scrollIntoView === undefined) {
  HTMLElement.prototype.scrollIntoView = testScrollIntoView;
}
if (HTMLElement.prototype.hasPointerCapture === undefined) {
  HTMLElement.prototype.hasPointerCapture = testHasPointerCapture;
}
if (HTMLElement.prototype.setPointerCapture === undefined) {
  HTMLElement.prototype.setPointerCapture = testPointerCapture;
}
if (HTMLElement.prototype.releasePointerCapture === undefined) {
  HTMLElement.prototype.releasePointerCapture = testPointerCapture;
}
