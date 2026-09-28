# OTA Fleet Rollout

基于 Spring Boot 的 OTA 设备机群升级编排服务。使用本地内存持久化（`ConcurrentHashMap` 仓库）
模拟设备、固件与升级活动，使灰度发布、失败回滚与并发上报下的状态结论可复现、可解释。

## 发布流程（含彩排与按批放行）

1. **注册设备 / 目标固件**（设备带当前版本，固件带平台与 `minSourceVersion`）。
2. **上线前彩排** `POST /api/campaigns/rehearse`：提交与创建活动完全相同的计划载荷，
   服务**只读**地判断整条计划能否执行，返回：
   - `feasible`：是否存在任何阻塞；
   - 按批次给出 `plannedDevices / coveredDevices / blockedDevices`；
   - 每台被阻塞设备带可区分 `reason`：
     - `DEVICE_NOT_FOUND`：设备不存在；
     - `INCOMPATIBLE_FIRMWARE`：设备平台与目标固件不一致；
     - `VERSION_CONSTRAINT_VIOLATED`：设备当前版本低于固件 `minSourceVersion`；
     - `DEVICE_BUSY_IN_OTHER_CAMPAIGN`：已被其他进行中活动占用；
     - `DUPLICATE_DEVICE_IN_PLAN`：同一设备在计划内（可跨批次）重复出现。

   彩排不创建活动、不占用设备、不写升级记录；即使 `feasible=false` 也无任何副作用。
3. **创建活动** `POST /api/campaigns`：与彩排共用校验规则；计划内设备重复会被
   `DUPLICATE_DEVICE_IN_PLAN` 拒绝。新增两个可选发布参数：
   - `controlled`（默认 `false`）：受控发布严格验收；
   - `requireConfirmationAfterFirstBatch`（默认 `false`）：首批结清后停在等待确认点。
4. **按批放行**：`advance` 下发下一批。推进前有**分批门禁**：
   - 上一批的每台设备都必须已有明确结论（成功 / 失败 / 已回滚，即脱离 `DISPATCHED`）；
   - 仍有设备未上报时以 `BATCH_NOT_SETTLED` 拒绝，并在消息中列出待结设备；
   - 活动处于暂停、失败率停下、等待确认状态时拒绝推进。
5. **首批人工确认**（可选）：首批结清后活动进入 `AWAITING_CONFIRMATION`，期间不下发任何后续批次；
   调用 `POST /api/campaigns/{id}/confirm-first-batch` 后回到 `RUNNING`，仍需再调用 `advance`
   才下发第二批。重复确认、并发确认、确认与推进并发时，同一批设备只可能下发一次
   （活动对象锁串行化，确认点只有一次成功迁移）。
6. **回滚 / 终止 / 暂停** 维持既有规则。

## 受控发布的严格验收

- 非受控发布（`controlled=false`，默认）：设备报告成功即视为成功，保持原有行为。
- 受控发布（`controlled=true`）：设备报告成功后，还必须**实际安装到目标固件版本**
  （上报 `reportedVersion` 必须等于活动目标版本）才算成功；
  - 报告成功但版本对不上 → 记录记为 `FAILED`，失败原因 `TARGET_VERSION_MISMATCH`，
    不按成功放行；
  - 随即按既有回滚规则自动回到设备 `lastConfirmedVersion`（此前已确认可用的版本），
    设备不会停在“看起来已升级、实际不可信”的版本上；设备状态 `ROLLED_BACK`、记录状态
    `ROLLED_BACK` 且仍保留失败原因；
  - 回滚设备计入失败率，与设备自报失败一样可触发活动停下。

## 领域模型

- **Device**：`deviceId / platform / currentVersion / lastConfirmedVersion / status / activeCampaignId`
- **Firmware**：`version / platform / minSourceVersion / checksum`，`minSourceVersion` 为可升级的最低源版本约束
- **Campaign**：分批灰度活动，`batches` 为设备分组，`failureThresholdPercent` 为失败率阈值，
  `controlled` 为受控发布开关，`requireConfirmationAfterFirstBatch` 为首批确认点开关
- **UpgradeRecord**：单设备在某活动中的升级记录，`lastAppliedSequence` 用于上报去重与乱序判定，
  `failureReason` 区分设备自报失败与严格验收版本对不上

## 状态流转

### 设备状态（DeviceStatus）
```
IDLE --(批次下发)--> UPGRADING --(上报成功)--> IDLE        （版本前进，lastConfirmedVersion 更新）
                              \-(上报失败)--> FAILED --(回滚)--> ROLLED_BACK
严格验收版本对不上：先 FAILED，随后自动回滚到 lastConfirmedVersion -> ROLLED_BACK
```

### 升级记录状态（UpgradeStatus）
```
DISPATCHED --(有效成功上报)--> SUCCEEDED
           \-(有效失败上报 / 严格验收版本对不上)--> FAILED --(回滚)--> ROLLED_BACK
```
记录进入终态后，任何后续上报不得造成状态倒退。

### 活动状态（CampaignStatus）
```
CREATED --(advance)--> RUNNING --(pause/失败率超阈值)--> PAUSED --(resume)--> RUNNING
RUNNING --(首批结清且配置了确认点)--> AWAITING_CONFIRMATION --(confirm-first-batch)--> RUNNING
RUNNING --(所有批次下发且全部记录终态)--> COMPLETED
CREATED/RUNNING/PAUSED/AWAITING_CONFIRMATION --(terminate)--> TERMINATED
```

三种“停住”状态可明确区分：

| 状态 | 含义 | 如何继续 |
|---|---|---|
| `PAUSED` + `autoPaused=false` | 人工暂停 | `resume` |
| `PAUSED` + `autoPaused=true` | 触发失败率停下条件 | 必须人工 `resume`，期间禁止推进 |
| `AWAITING_CONFIRMATION` | 首批已结清，等待发布负责人确认 | `confirm-first-batch` 后再 `advance` |

## 灰度与回滚规则

1. **上报幂等与有序性**：设备上报携带单调递增 `sequence`。仅接受严格大于
   `lastAppliedSequence` 的首次结论；重复（同序号）与乱序/迟到（更小序号）上报以
   `STALE_REPORT` 拒绝，不重复计数、不改变状态。
2. **上报校验**：缺失版本或平台信息 → `MISSING_VERSION_OR_PLATFORM`；
   上报平台与注册平台不一致 → `PLATFORM_MISMATCH`。
3. **互斥下发**：同一设备同一时刻只能属于一个进行中活动（含等待确认的活动），冲突时拒绝
   `DEVICE_BUSY_IN_OTHER_CAMPAIGN`。
4. **兼容约束**：目标固件平台与设备不一致 → `INCOMPATIBLE_FIRMWARE`；
   设备当前版本低于 `minSourceVersion` → `VERSION_CONSTRAINT_VIOLATED`，不予下发。
5. **自动停下**：每次有效上报后重算活动失败率 `failed / finished * 100`
   （失败与已回滚均计入失败），超过阈值即自动暂停（`PAUSED` + `autoPaused=true`），
   暂停状态下 `advance` 被拒绝，需人工 `resume`。失败率停下优先于首批等待确认。
6. **分批门禁**：推进下一批前上一批必须全部结清（见“发布流程”）。
7. **并发一致性**：批次推进、首批确认按活动对象加锁串行化；上报按记录对象加锁，
   并发重复上报恰好生效一次；并发重复确认恰好放行一次。
8. **失败回滚**：仅 `FAILED` 记录可回滚，回滚目标为设备 `lastConfirmedVersion`
   （最近一次确认可用版本）；回滚后设备释放，`GET /api/devices/version-distribution`
   可准确查询机群版本分布。严格验收版本对不上时该回滚自动完成。

## 兼容范围

- 旧的创建活动 JSON（不含 `controlled`、`requireConfirmationAfterFirstBatch`）仍可直接使用，
  两者默认均为 `false`，行为与旧版本一致（非受控、无首批确认点）。
- 彩排端点使用与创建活动相同的请求体，只读、不改变任何状态。
- 既有 API 路径与拒绝语义保持不变，仅新增端点、状态枚举值与拒绝原因。

## API 概览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/devices/register` | 设备注册 |
| POST | `/api/devices/heartbeat` | 心跳（校验平台一致性） |
| POST | `/api/devices/report` | 升级结果上报 |
| GET  | `/api/devices/version-distribution` | 机群版本分布 |
| POST | `/api/campaigns/firmware` | 注册固件 |
| POST | `/api/campaigns/rehearse` | 上线前彩排（只读，无副作用） |
| POST | `/api/campaigns` | 创建分批灰度活动 |
| POST | `/api/campaigns/{id}/advance` | 推进下一批次（受分批门禁约束） |
| POST | `/api/campaigns/{id}/confirm-first-batch` | 首批结清后人工确认 |
| POST | `/api/campaigns/{id}/pause` `/resume` `/terminate` | 暂停/恢复/终止 |
| POST | `/api/campaigns/{id}/devices/{deviceId}/rollback` | 回滚指定设备 |

被拒绝的操作返回 HTTP 409，body 含可区分的 `reason`（见 `RejectReason` 枚举）与 `message`。

## 本地验证

```bash
# 运行全部测试（含彩排无副作用、阻塞原因区分、严格验收版本对不上、
# 上一批未结清拒绝推进、首批等待确认、并发重复确认，
# 以及重复/乱序上报、回滚、失败率暂停、版本分布回归用例；
# 日志会打印关键输入与判定依据）
mvn test

# 启动服务
mvn spring-boot:run

# 手工验证示例：彩排 -> 创建（受控 + 首批确认点）-> 首批 -> 确认 -> 第二批
curl -X POST localhost:8080/api/campaigns/firmware -H 'Content-Type: application/json' \
  -d '{"version":"2.0.0","platform":"linux-arm64","minSourceVersion":"1.0.0","checksum":"sha256:aaa"}'
curl -X POST localhost:8080/api/devices/register -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-1","platform":"linux-arm64","initialVersion":"1.0.0"}'
curl -X POST localhost:8080/api/campaigns/rehearse -H 'Content-Type: application/json' \
  -d '{"campaignId":"camp-1","targetFirmwareVersion":"2.0.0","batches":[["dev-1"]],
       "failureThresholdPercent":50,"controlled":true,"requireConfirmationAfterFirstBatch":true}'
curl -X POST localhost:8080/api/campaigns -H 'Content-Type: application/json' \
  -d '{"campaignId":"camp-1","targetFirmwareVersion":"2.0.0","batches":[["dev-1"]],
       "failureThresholdPercent":50,"controlled":true,"requireConfirmationAfterFirstBatch":true}'
curl -X POST localhost:8080/api/campaigns/camp-1/advance
# 受控发布：reportedVersion 必须是目标版本 2.0.0，否则判失败并自动回滚
curl -X POST localhost:8080/api/devices/report -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-1","campaignId":"camp-1","sequence":1,"success":true,"reportedVersion":"2.0.0","platform":"linux-arm64"}'
curl localhost:8080/api/devices/version-distribution
```

## 测试覆盖

- `DeviceReportTest`：重复上报去重、乱序/迟到上报不倒退、缺失信息与平台不匹配的可区分拒绝
- `CampaignConcurrencyTest`：并发推进串行化、并发重复上报恰好生效一次、失败率超阈值自动暂停
- `RollbackAndCompatibilityTest`：失败回滚到最近确认版本、非失败记录回滚拒绝、
  平台/版本兼容约束、设备活动互斥、混合结果下的机群版本分布
- `ReleaseFlowTest`：彩排可行/失败均无副作用、五类阻塞原因可区分、
  严格验收版本对不上即失败并自动回滚、非受控行为不变、上一批未结清拒绝推进、
  首批等待确认与三种停住状态区分、并发重复确认与确认后推进只下发一次
- `ReleaseFlowHttpTest`：彩排端点返回明细、旧创建 JSON 缺省新字段仍可创建（兼容性）
