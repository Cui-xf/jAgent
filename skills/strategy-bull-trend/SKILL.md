---
name: strategy-bull-trend
description: 【策略】多头趋势战法。MA5≥MA10≥MA20 多头排列 + 不追高 + 回踩低吸；用于常规个股分析的默认策略
---

# 策略 Skill：多头趋势（Bull Trend）

> 类别：B / 策略战法 · category=trend · 核心理念：1 严进 · 2 趋势 · 3 效率

## 适用场景

- 常规个股分析的**默认策略**。
- 市场整体处于上升 / 震荡偏多；目标个股处于上升阶段。
- 目标："趋势向上 + 风险可控 + 不追高"。

## 必需数据（required_tools）

执行本策略前，请通过 `skill(name)` 依次加载并执行下列数据类 skill（名称以当前
skill catalog 为准，按语义匹配）：

1. 实时行情（realtime / quote 类 skill）
2. 日线历史 K 线（daily / history 类 skill，窗口 ≥ 60 个交易日）
3. 技术指标与趋势评分（trend / analyze 类 skill，输出 MA/MACD/RSI/bias/signal_score）
4. 新闻舆情（news / search 类 skill，近 3 天，维度 latest_news + risk_check）
5. 筹码分布（可选；chip 类 skill）

若某项 skill 不存在，可改用 `shell_execute` 调用等价的本地脚本；仍缺失时照原则继续，
并在最终输出的 `data_completeness` 里置 false。

## 判定框架

### 1. 趋势确认（最高优先级）
- MA5 ≥ MA10 ≥ MA20 且 MA20 斜率向上 → 多头结构成立。
- 价格明显跌破 MA20 → 看多权重下调；跌破 MA60 → 放弃多头判定。
- `signal_score ≥ 70` 才允许给出"买入/加仓"级别建议。

### 2. 位置与节奏（避免追高）
- 当前价距离 MA5 的乖离率 `bias_ma5`：
  - < 2%：理想买入区
  - 2–5%：可接受，提示"等回踩"
  - > 5%：**一律提示等回踩**，不给买入建议
- 若价格贴近关键阻力，需同时看量能是否放大。

### 3. 量价验证
- 突破阻力日必须放量（成交量 ≥ 5 日均量 1.5 倍）。
- 缩量上涨 → 记为风险点（`risk_factors`）。
- 放量滞涨（量放大但收小阳 / 十字星）→ 提示分歧可能见顶。

### 4. 风险排查
- 若近 3 日新闻命中"业绩爆雷 / 监管处罚 / 重大诉讼 / 财务造假 / 商誉减值"等关键词 →
  直接降级为"观望"并在 `risk_alerts` 标注。
- 板块整体下跌 > 3% 时，个股多头信号可信度打 7 折。

## 输出要求

按 system prompt 里的决策仪表盘 JSON 结构回复，并确保：

- `strategy_used = "strategy-bull-trend"`
- `battle_plan.sniper_points`：
  - `ideal_buy` ≈ MA5 水平
  - `secondary_buy` ≈ MA10 水平
  - `stop_loss` ≈ MA20 下方 1-2%（或前低）
  - `take_profit` ≈ 前高 / 整数关口
- `action_checklist` 覆盖：多头排列、乖离率合理、量能配合、筹码集中度、无利空、估值水平。
- `buy_reason` 中必须明确提到"多头趋势策略"以及你判定的关键数据点。

## 评分调整建议（在基线上叠加）

| 情形 | sentiment_score |
|---|---|
| 多头排列 + 趋势强度良好（signal_score ≥ 75） | +12 |
| 回踩 MA5/MA10 后企稳收阳 | +8 |
| 放量突破关键阻力 | +10 |
| bias_ma5 > 5%（偏离过大） | -6 |
| 跌破 MA20 / 趋势转弱 | -12 |
| 风险排查命中重大利空 | 直接降到 ≤ 40（观望） |

## 典型结论模板

- 强烈看多："多头排列稳固 + 放量突破 X，量价配合，回踩 MA5（Y 元）是理想买点。"
- 看多回踩："趋势完好但 bias_ma5=%X 偏高，等回踩 MA5/MA10 再做决策。"
- 观望："价格跌破 MA20，多头结构破坏，等重新站上再说。"
