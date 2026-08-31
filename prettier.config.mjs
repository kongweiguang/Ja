// @author kongweiguang

/**
 * 前端格式化采用单一、可复现的配置，避免 JSX 因人工压行而失去结构层次。
 * 这里只约束排版，不承载架构规则；依赖方向和测试归属仍由 architecture Gate 负责。
 */
export default {
  printWidth: 100,
  tabWidth: 2,
  useTabs: false,
  semi: true,
  singleQuote: false,
  trailingComma: "all",
  endOfLine: "lf",
};
