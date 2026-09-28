# OTA Fleet Rollout

基于 Spring Boot 的 OTA 设备机群升级编排服务。使用本地内存持久化（`ConcurrentHashMap` 仓库）
模拟设备、固件与升级活动，使灰度发布、失败回滚与并发上报下的状态结论可复现、可解释。

## 领域模型

- **Device**：`deviceId / platform / currentVersion / lastConfirmedVersion / status / activeCampaignId`
- **Firmware**：`version / platform / minSourceVersion / checksum`，`minSourceVersion` 为可升级的最低源版本约束
- **Campaign**：分批灰度活动，`batches` 为设备分组，`failureThresholdPercent` 为失败率阈值；
  `controlled` 表示受控发布（严格验收），`waitAfterFirstBatch` 表示首批结清后停在等待人工确认
- **UpgradeRecord**：单设备在某活动中的升级记录，`lastAppliedSequence` 用于上报去重与乱序判定，
  `failureReason` 给出明确失败原因（`DEVICE_REPORTED_FAILURE` / `REPORTED_VERSION_MISMATCH`）

## 状态流转

### 设备状态（DeviceStatus）
```
IDLE --(批次下发)--> UPGRADING --(上报成功)--> IDLE        （版本前进，lastConfirmedVersion 更新）
                              \-(上报失败)--> FAILED --(回滚)--> ROLLED_BACK
```

### 升级记录状态（UpgradeStatus）
```
DISPATCHED --(有效成功上报)--> SUCCEEDED
           \-(有效失败上报)--> FAILED --(回滚)--> ROLLED_BACK
```
记录进入终态后，任何后续上报不得造成状态倒退。

### 活动状态（CampaignStatus）
```
CREATED --(advance)--> RUNNING --(pause/失败率超阈值)--> PAUSED --(resume)--> RUNNING
RUNNING --(首批结清且 waitAfterFirstBatch)--> AWAITING_CONFIRMATION --(confirm)--> RUNNING
RUNNING --(所有批次下发且全部记录终态)--> COMPLETED
CREATED/RUNNING/PAUSED/AWAITING_CONFIRMATION --(terminate)--> TERMINATED
```

三种“停下”状态含义不同，可区分：

| 状态 | `status` | `autoPaused` | 含义 | 恢复方式 |
|---|---|---|---|---|
| 人工暂停 | `PAUSED` | `false` | 人工 `pause` 主动暂停 | `resume` |
| 触发停下条件 | `PAUSED` | `true` | 失败率超阈值，自动暂停 | 必须人工 `resume`，未恢复前 `advance` 以 `STOP_CONDITION_TRIGGERED` 拒绝 |
| 首批等待确认 | `AWAITING_CONFIRMATION` | `false` | 首批已结清，等待人工放行 | `confirm-first-batch`，确认本身不下发，之后再 `advance` |

## 上线前彩排（Rehearsal）

`POST /api/campaigns/rehearse` 接受与创建活动相同的计划报文，执行**纯只读**评估：

- 不创建活动、不占用设备、不产生升级记录（无论结论是否可执行）。
- 按批次给出 `plannedCount`（计划设备数）、`coveredCount`（预计可覆盖的唯一设备数）
  与 `blockedDevices`（被阻塞设备及各自原因）。
- 可分辨的阻塞原因：
  `DEVICE_NOT_FOUND`（设备不存在）、`INCOMPATIBLE_FIRMWARE`（平台不满足）、
  `VERSION_CONSTRAINT_VIOLATED`（源版本低于 `minSourceVersion`）、
  `DEVICE_BUSY_IN_OTHER_CAMPAIGN`（被其他进行中活动占用）、
  `DUPLICATE_DEVICE_IN_PLAN`（同一设备在计划内重复出现）。
- 任一批次存在阻塞则整体 `executable=false`；目标固件不存在直接以 `FIRMWARE_NOT_FOUND` 拒绝。
- 正式 `POST /api/campaigns` 创建路径同样拒绝计划内重复设备（`DUPLICATE_DEVICE_IN_PLAN`）。

## 受控发布与严格验收

创建活动时 `controlled=true` 进入受控发布：设备报告 `success=true` 时，
其 `reportedVersion` 必须**恰好等于目标固件版本**才算本批升级成功。

- 报告成功但实际版本对不上：记录明确失败（`UpgradeStatus=ROLLED_BACK`，
  `failureReason=REPORTED_VERSION_MISMATCH`），并立即按现有回滚规则把设备恢复到
  `lastConfirmedVersion`（此前确认可用的状态），不会停在“看起来已升级、实际不可信”的状态。
- 版本完全一致才更新版本与 `lastConfirmedVersion`，释放设备。
- 非受控发布（`controlled=false`，默认，兼容旧报文）保持原行为：成功上报即放行。

## 分批放行门禁与首批确认

- 每次 `advance` 推进下一批前，上一批的每台设备都必须已有明确结论（记录进入终态）。
  仍有设备未上报时以 `PREVIOUS_BATCH_NOT_SETTLED` 拒绝。
- 当前失败率已达到活动停下条件（`PAUSED` + `autoPaused=true`）时，
  `advance` 以 `STOP_CONDITION_TRIGGERED` 拒绝，人工 `resume` 后才能继续。
- `waitAfterFirstBatch=true` 的活动，首批结清后自动进入 `AWAITING_CONFIRMATION`：
  - 确认前 `advance` 被拒绝（`AWAITING_FIRST_BATCH_CONFIRMATION`），后续批次不下发；
  - 调用 `POST /api/campaigns/{id}/confirm-first-batch` 确认后回到 `RUNNING`，
    确认动作本身不下发，随后的 `advance` 才真正下发下一批；
  - 重复确认 / 并发确认 / 确认与推进同时发生时，状态变更与批次下发都按活动对象加锁串行化，
    同一批设备恰好下发一次；重复或非确认点上的确认以 `CONFIRMATION_NOT_REQUIRED` 拒绝。
- 未配置该开关的活动行为不变：首批结清后可直接 `advance`。

## 灰度与回滚规则

1. **上报幂等与有序性**：设备上报携带单调递增 `sequence`。仅接受严格大于
   `lastAppliedSequence` 的首次结论；重复（同序号）与乱序/迟到（更小序号）上报以
   `STALE_REPORT` 拒绝，不重复计数、不改变状态。
2. **上报校验**：缺失版本或平台信息 → `MISSING_VERSION_OR_PLATFORM`；
   上报平台与注册平台不一致 → `PLATFORM_MISMATCH`。
3. **互斥下发**：同一设备同一时刻只能属于一个进行中活动，冲突时拒绝
   `DEVICE_BUSY_IN_OTHER_CAMPAIGN`。
4. **兼容约束**：目标固件平台与设备不一致 → `INCOMPATIBLE_FIRMWARE`；
   设备当前版本低于 `minSourceVersion` → `VERSION_CONSTRAINT_VIOLATED`，不予下发。
5. **自动暂停**：每次有效上报后重算活动失败率
   `failed / finished * 100`，超过阈值即自动暂停（`autoPaused=true`），
   暂停状态下 `advance` 被拒绝，需人工 `resume` 后方可继续推进。
6. **并发一致性**：批次推进按活动对象加锁串行化；上报按记录对象加锁，
   并发重复上报恰好生效一次。
7. **失败回滚**：仅 `FAILED` 记录可回滚，回滚目标为设备 `lastConfirmedVersion`
   （最近一次确认可用版本）；回滚后设备释放，`GET /api/devices/version-distribution`
   可准确查询机群版本分布。

## API 概览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/devices/register` | 设备注册 |
| POST | `/api/devices/heartbeat` | 心跳（校验平台一致性） |
| POST | `/api/devices/report` | 升级结果上报 |
| GET  | `/api/devices/version-distribution` | 机群版本分布 |
| POST | `/api/campaigns/firmware` | 注册固件 |
| POST | `/api/campaigns/rehearse` | 上线前彩排（只读，无副作用） |
| POST | `/api/campaigns` | 创建分批灰度活动（可选 `controlled`、`waitAfterFirstBatch`） |
| POST | `/api/campaigns/{id}/advance` | 推进下一批次（带放行门禁） |
| POST | `/api/campaigns/{id}/confirm-first-batch` | 首批结清后人工确认放行 |
| POST | `/api/campaigns/{id}/pause` `/resume` `/terminate` | 暂停/恢复/终止 |
| POST | `/api/campaigns/{id}/devices/{deviceId}/rollback` | 回滚指定设备 |

被拒绝的操作返回 HTTP 409，body 含可区分的 `reason`（见 `RejectReason` 枚举）与 `message`。

## 本地验证

```bash
# 运行全部测试（含彩排、严格验收、分批门禁与首批确认，
# 以及重复/乱序上报、并发推进、失败回滚等既有用例，日志会打印关键输入与判定依据）
mvn test

# 启动服务
mvn spring-boot:run

# 手工验证示例
curl -X POST localhost:8080/api/campaigns/firmware -H 'Content-Type: application/json' \
  -d '{"version":"2.0.0","platform":"linux-arm64","minSourceVersion":"1.0.0","checksum":"sha256:aaa"}'
curl -X POST localhost:8080/api/devices/register -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-1","platform":"linux-arm64","initialVersion":"1.0.0"}'
curl -X POST localhost:8080/api/campaigns -H 'Content-Type: application/json' \
  -d '{"campaignId":"camp-1","targetFirmwareVersion":"2.0.0","batches":[["dev-1"]],"failureThresholdPercent":50}'
curl -X POST localhost:8080/api/campaigns/camp-1/advance
curl -X POST localhost:8080/api/devices/report -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-1","campaignId":"camp-1","sequence":1,"success":true,"reportedVersion":"2.0.0","platform":"linux-arm64"}'
curl localhost:8080/api/devices/version-distribution

# 上线前彩排（与创建同报文，不落任何数据）
curl -X POST localhost:8080/api/campaigns/rehearse -H 'Content-Type: application/json' \
  -d '{"campaignId":"plan-1","targetFirmwareVersion":"2.0.0","batches":[["dev-1"]],"failureThresholdPercent":50,"controlled":true,"waitAfterFirstBatch":true}'

# 受控发布 + 首批后人工确认：首批结清后状态变为 AWAITING_CONFIRMATION，
# 确认后再推进第二批
curl -X POST localhost:8080/api/campaigns/camp-1/confirm-first-batch
curl -X POST localhost:8080/api/campaigns/camp-1/advance
```

### 兼容范围

- 创建活动请求新增的 `controlled`、`waitAfterFirstBatch` 均为可选，缺省为 `false`，
  旧请求（不带这两个字段）行为与原先完全一致（非受控发布、首批后不等待确认）。
- 非受控发布下，上报成功即放行的旧验收行为保持不变。

## 测试覆盖

- `DeviceReportTest`：重复上报去重、乱序/迟到上报不倒退、缺失信息与平台不匹配的可区分拒绝
- `CampaignConcurrencyTest`：并发推进串行化、并发重复上报恰好生效一次、失败率超阈值自动暂停
- `RollbackAndCompatibilityTest`：失败回滚到最近确认版本、非失败记录回滚拒绝、
  平台/版本兼容约束、设备活动互斥、混合结果下的机群版本分布
- `RehearsalTest`：彩排可执行计划、无活动/占用/记录副作用，设备不存在、平台不兼容、
  源版本不满足、被占用、计划内重复等不同阻塞原因分辨，目标固件缺失快速失败
- `ControlledReleaseAndGateTest`：严格验收下上报版本对不上即失败并自动回滚到确认版本、
  版本一致才放行、非受控发布旧行为不变、上一批未结清拒绝推进、停下条件未恢复拒绝推进
- `FirstBatchConfirmationTest`：首批结清停在等待确认、确认前不下发、确认后才推进、
  重复确认拒绝、无门禁活动拒绝确认、并发确认与推进下同一批仅下发一次，
  以及等待确认/人工暂停/触发停下条件三种状态可区分
