# OTA 设备机群升级编排服务

基于 Spring Boot 的本地 OTA 升级编排能力演示：用 **内存结构 + JSON 文件快照** 模拟设备、固件与升级活动的本地持久化，重点保证灰度发布、失败回滚、并发上报下的状态结论 **可复现、可解释**。

- 设备注册 / 心跳 / 升级结果上报
- 分批灰度活动：顺序推进、暂停、恢复、终止
- 上报幂等：重复、乱序、迟到上报不重复计数、状态不倒退
- 失败与校验不通过自动回滚到最近一次确认可用版本（LKG）
- 固件平台 / 最低基线版本兼容约束，不满足不下发
- 失败率超阈值自动暂停，阻止继续下发
- 机群版本分布查询（回滚后数据准确）
- 所有写操作在同一把读写写锁内完成，并落盘 JSON 快照，重启可恢复

## 1. 状态流转

### 1.1 升级活动 `CampaignStatus`

```
PENDING ──advance(激活第一批)──> RUNNING
RUNNING ──pause(手动)──────────> PAUSED(reason=MANUAL)
RUNNING ──失败率达到阈值(上报)──> PAUSED(reason=AUTO_FAILURE_RATE)
PAUSED  ──resume──────────────> RUNNING
RUNNING/PAUSED ──terminate────> TERMINATED     (终态)
RUNNING ──最后一批全部终态(上报)─> COMPLETED    (终态，自动)
```

- `advance` 仅在 `PENDING/RUNNING` 下允许；`PAUSED` 必须先 `resume`；`TERMINATED/COMPLETED` 拒绝推进。
- 当前批次存在未终态任务时，`advance` 被拒绝（`CAMPAIGN_NOT_RUNNABLE`），不能跨批次下发。

### 1.2 批次 `BatchStatus`

```
PENDING ──所属批次被激活──> ACTIVE ──批次内任务全部终态──> COMPLETED
```

### 1.3 单设备升级任务 `TaskStatus`（只前进，不回退）

```
ASSIGNED ──批次激活下发──> DISPATCHED
DISPATCHED ──上报 SUCCESS 且上报版本==目标版本──> SUCCESS
DISPATCHED ──上报 FAILURE 或版本校验不通过，且存在 LKG──> ROLLED_BACK
DISPATCHED ──上报 FAILURE/校验不通过，但无 LKG 基线──────> FAILED
```

- `SUCCESS/ROLLED_BACK/FAILED` 均为终态。
- SUCCESS 时把目标版本更新为设备的 `lastKnownGoodVersion`（新的回滚基线）。
- ROLLED_BACK 时设备 `currentVersion` 回滚为 LKG，机群版本分布随之变化。

## 2. 上报的幂等、乱序与拒绝规则

每个上报必须携带单调递增的 `seq`。服务端在任务上记录最近已接受的 `lastReportSeq`：

| 情况 | 判定 | 结论 |
| --- | --- | --- |
| `seq == lastReportSeq` | `DUPLICATE_REPORT` | 拒绝，不增加 attempts，状态不变 |
| `seq <  lastReportSeq` | `STALE_REPORT` | 拒绝（乱序/迟到），即使内容是 FAILURE 也不把 SUCCESS 拉回 |
| 任务已终态且 `seq > lastReportSeq` | `DUPLICATE_REPORT` | 拒绝，终态结论不可被迟到结论覆盖 |
| 批次未激活（任务 ASSIGNED）就上报 | `NO_ACTIVE_TASK` | 拒绝 |
| 缺少 platform / version | `MISSING_PLATFORM` / `MISSING_VERSION` | 拒绝 |
| 上报平台与注册平台不一致 | `PLATFORM_MISMATCH` | 拒绝 |
| SUCCESS 但上报版本 != 目标版本 | 接受为一次尝试，**校验不通过→回滚** | `ROLLED_BACK`，失败原因 `TARGET_VERSION_MISMATCH` |
| 设备自报 FAILURE | 接受为一次尝试，**回滚到 LKG** | `ROLLED_BACK`（无 LKG 则 `FAILED`） |

> 只有"被接受且推进状态机"的上报才会让 `attempts + 1`，因此重复/乱序上报不会造成升级次数重复计数。

## 3. 灰度、兼容与回滚规则

- **分批推进**：活动由多个有序批次组成；同一时刻只有一个 ACTIVE 批次；批次全部终态后才能激活下一批。
- **单设备唯一归属**：创活动时校验，设备已属于某个 `PENDING/RUNNING/PAUSED` 活动时拒绝（`DEVICE_IN_ANOTHER_CAMPAIGN`）；活动 COMPLETED/TERMINATED 后释放。
- **兼容约束（创建时逐设备校验，不满足则整个活动不创建，绝不下发）**：
  - 平台：固件平台须与设备平台一致，或设备平台在固件 `compatiblePlatforms` 内，否则 `FIRMWARE_PLATFORM_UNSUPPORTED`；
  - 基线：设备当前版本须 `>= firmware.minBaseVersion`，否则 `FIRMWARE_BASE_VERSION_TOO_OLD`。
- **失败率自动暂停**：以全部已终态任务计 `failureRate = (ROLLED_BACK + FAILED) / 已终态数`，`failureRate >= 阈值` 时活动自动 `PAUSED(AUTO_FAILURE_RATE)`；暂停期间 `advance` 被拒绝，后续批次任务保持 `ASSIGNED` 不会下发；`resume` 后才能继续。最后一批全部终态时优先 COMPLETED。
- **回滚**：回滚目标为设备的 `lastKnownGoodVersion`（出厂版本即首个 LKG；每次确认成功后前移）。回滚结果可在版本分布接口准确反映。

## 4. 本地持久化

- 快照文件：`target/ota-store.json`（可用 `ota.store.file` 覆盖，见 `application.properties`）。
- 写操作（持写锁）结束后全量落盘：先写 `.tmp` 再 rename，进程重启后 `@PostConstruct` 自动加载。
- 并发控制：服务层所有变更在 `FleetStore` 的公平 `ReentrantReadWriteLock` 写锁内完成，读操作使用读锁。

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/ota/devices` | 注册设备（幂等） |
| POST | `/api/ota/devices/heartbeat` | 心跳 |
| GET  | `/api/ota/devices/{id}` | 查询设备 |
| GET  | `/api/ota/devices/versions?platform=` | 机群版本分布 |
| POST | `/api/ota/firmwares` | 注册固件（含 minBaseVersion / compatiblePlatforms） |
| POST | `/api/ota/campaigns` | 创建分批灰度活动 |
| GET  | `/api/ota/campaigns/{id}` | 活动详情与计数 stats |
| POST | `/api/ota/campaigns/{id}/advance` | 激活下一批 |
| POST | `/api/ota/campaigns/{id}/pause` | 手动暂停 |
| POST | `/api/ota/campaigns/{id}/resume` | 恢复 |
| POST | `/api/ota/campaigns/{id}/terminate` | 终止 |
| POST | `/api/ota/reports` | 升级结果上报 |

拒绝统一返回 `400 {"ok":false,"code":"<原因码>","message":"..."}`，原因码取
`MISSING_VERSION / MISSING_PLATFORM / PLATFORM_MISMATCH / TARGET_VERSION_MISMATCH /
DUPLICATE_REPORT / STALE_REPORT / NO_ACTIVE_TASK / DEVICE_IN_ANOTHER_CAMPAIGN /
FIRMWARE_PLATFORM_UNSUPPORTED / FIRMWARE_BASE_VERSION_TOO_OLD / CAMPAIGN_NOT_RUNNABLE /
CAMPAIGN_ALREADY_PAUSED / CAMPAIGN_TERMINATED / NO_MORE_BATCHES / DEVICE_NOT_FOUND / ...`。

### 手工验证示例（curl）

```bash
# 1) 设备与固件
curl -s localhost:8080/api/ota/devices -H 'Content-Type: application/json' \
  -d '{"deviceId":"d1","platform":"linux-arm64","currentVersion":"1.0.0"}'
curl -s localhost:8080/api/ota/firmwares -H 'Content-Type: application/json' \
  -d '{"firmwareId":"fw2","platform":"linux-arm64","version":"2.0.0","minBaseVersion":"1.0.0"}'

# 2) 创建两批灰度活动（失败率阈值 0.5）并激活第一批
curl -s localhost:8080/api/ota/campaigns -H 'Content-Type: application/json' \
  -d '{"campaignId":"c1","firmwareId":"fw2","failureRateThreshold":0.5,"batches":[["d1"],["d2"]]}'
curl -s -X POST localhost:8080/api/ota/campaigns/c1/advance

# 3) 上报成功；重复/乱序上报会被按原因拒绝
curl -s localhost:8080/api/ota/reports -H 'Content-Type: application/json' \
  -d '{"campaignId":"c1","deviceId":"d1","seq":1,"outcome":"SUCCESS","reportedVersion":"2.0.0","platform":"linux-arm64"}'

# 4) 查看活动计数与机群版本分布
curl -s localhost:8080/api/ota/campaigns/c1
curl -s 'localhost:8080/api/ota/devices/versions?platform=linux-arm64'
```

## 6. 本地验证方法

```bash
# 运行应用（快照写入 target/ota-store.json）
mvn spring-boot:run

# 全部单元测试（20 个），日志中打印关键输入与判定依据（搜索 [输入] / [判定]）
mvn test
```

测试覆盖：

- `DeviceFirmwareTest`：注册字段缺失可区分拒绝、注册幂等、心跳平台不匹配；
- `ReportOrderingTest`：重复/乱序/迟到上报不重复计数且状态不倒退、缺失/不兼容字段原因可区分、FAILURE 与版本不匹配回滚 LKG、未激活上报拒绝；
- `CampaignGrayscaleTest`：批次顺序推进与未结清拦截、失败率超阈值自动暂停且阻断下一批、设备不能同时进两个活动、兼容约束创建期拦截、暂停/终止守卫；
- `ConcurrencyTest`：20 设备 ×5 并发重复上报仅 20 次被接受且 attempts 守恒、8 并发 advance 仅一个成功、并发上报后计数守恒；
- `PersistenceVersionTest`：快照落盘与重启恢复、语义化版本比较；
- `WebApiTest`：HTTP 全链路与错误原因码。

测试日志示例（关键输入与判定依据）：

```
[输入] d1 seq=10 重复上报 -> accepted=false, reason=DUPLICATE_REPORT, attempts=1
[输入] d1 seq=5 迟到 FAILURE -> accepted=false, reason=STALE_REPORT, status 保持=SUCCESS
auto paused by failure rate: campaign=c1, failureRate=0.5, threshold=0.5 ...
rolled back: campaign=c1, device=d2, from=2.0.0, toLkg=1.0.0
[判定] 回滚后机群版本分布: total=1, byVersion={1.0.0=1}
```
