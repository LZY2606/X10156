# Webhook 投递实验室（Webhook Delivery Lab）

在**不访问任何真实接收方**的前提下，验证 Webhook 投递的签名、重试与乱序处理。
所有投递都只发往一个**进程内可编程接收器**；程序不会发起任何外部 URL 请求。

## 运行

```bash
./gradlew classes                                   # 安装检查
./gradlew test                                      # 全部测试（23 个）
./gradlew run --args='--host 127.0.0.1 --port 5232' # 演示
# 打开 http://127.0.0.1:5232 ，页面标题为「Webhook 投递实验室」
```

可选参数：`--data <dir>`（持久化目录，默认 `lab-data`）、`--exit-on-crash`（崩溃点真的 `halt(3)`，用于进程级恢复测试）。

## 能做什么

- **端点配置 / 可编程接收器**：成功、响应超时、连接断开、`429` + `Retry-After`、
  五类服务端状态（500/501/502/503/504）、以及“已处理但响应丢失”。
- **虚拟时间队列**：事件入队后按计划时刻投递；“单步推进”跳到下一个可执行计划点，
  也可“前进 N 毫秒”。没有任何真实 sleep。
- **签名**：HMAC-SHA256。规范化签名输入覆盖 HTTP 方法、路径、**实际发送 body 字节的 SHA-256**、
  选定请求头（排序）、事件 id、attempt id、时间戳；签名头本身不参与签名。每次重试复用
  事件 id 与原始 payload 字节，但使用全新的 attempt id 与时间戳（因此签名不同）。
- **Retry-After**：同时支持增量秒数与 HTTP-date（RFC 1123 等）；超过端点退避上限即进入死信。
- **确定性退避**：`delay = min(initial * multiplier^(n-1), max) * (0.5 + 0.5 * 抖动)`，
  抖动来自可注入种子 + 事件 id 的确定性伪随机流，跨进程/重启结果一致。
- **暂停 / 恢复**：暂停只阻止**新的尝试**，不取消已经送达接收器的处理；恢复后严格按
  原始 `scheduledAt` 顺序执行，不会用“当前时刻 + 新退避”重排。
- **死信重放**：重放创建一条**新投递链**，通过 `replayOfChain` 指回原链；旧链及其尝试
  永不被修改。
- **幂等**：接收器维护“已处理事件”账本。重试（含崩溃后恢复的重发）只得到幂等确认，
  业务副作用不重复执行。

## 崩溃点与恢复

可对下一次操作武装三个崩溃点：

| 崩溃点 | 时机 | 重启后 |
| --- | --- | --- |
| `after_schedule` | 调度记录已 fsync、尚未发送 | PENDING 尝试仍在，按原计划时刻发送 |
| `before_send` | in-flight 记录已提交、发送前 | 旧尝试标记 `RECOVERED`，新建尝试重发 |
| `after_send` | 接收器已返回（可能已提交副作用）、响应落盘前 | 旧尝试 `RECOVERED`，重发并走幂等去重 |

所有状态（端点、事件、尝试、时钟、接收器已处理集合）都来自**单条 append-only、
每行 fsync 的 JSONL 日志**（`<data>/lab.log`），启动时重放重建，因此结果确定且不丢事件。

## HTTP API

- `GET /api/state` 全量快照（端点、链、每次尝试、计划队列、时钟）
- `POST /api/endpoints` / `PUT /api/endpoints/{id}` 端点（PUT 为部分更新，不改暂停位）
- `POST /api/endpoints/{id}/pause` · `/resume`
- `POST /api/events` 发送事件（重复 `eventId` 返回 `409`）
- `POST /api/clock/step` · `POST /api/clock/advance {millis}`
- `GET /api/chains/{chainId}` · `POST /api/chains/{chainId}/replay`
- `POST /api/admin/jitter-seed {seed}` · `POST /api/admin/crash {point}` · `POST /api/admin/clear-crash`

## 代码结构

- `Signer` / `Backoff`：签名规范化与确定性退避、Retry-After 解析
- `Receiver`：进程内接收器（验签、幂等账本、六类行为模拟）
- `LogStore`：append-only JSONL 持久化与记录编解码
- `LabEngine`：虚拟时钟、调度树、发送/落盘两阶段、重试/死信、重放、启动恢复
- `Main`：JDK 内置 `HttpServer`（仅提供控制台与 JSON API；不用于投递）
- `Json`：零依赖 JSON 解析/写入（支持 record 序列化）
