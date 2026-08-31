// @author kongweiguang
/** 为什么这是拒绝样例：UI 不应直接持有 storage 副作用。 */
export function persistFromView(): void {
  localStorage.setItem("state", "x");
}
