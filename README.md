# OTA Fleet Rollout

基于 Spring Boot 的 OTA 设备机群升级编排服务。使用本地内存持久化（`ConcurrentHashMap` 仓库）
模拟设备、固件与升级活动，使灰度发布、失败回滚与并发上报下的状态结论可复现、可解释。

## 领域模型

- **Device**：`deviceId / platform / currentVersion / lastConfirmedVersion / status / activeCampaignId`
- **Firmware**：`version / platform / minSourceVersion / checksum`，`minSourceVersion` 为可升级的最低源版本约束
- **Campaign**：分批灰度活动，`batches` 为设备分组，`failureThresholdPercent` 为失败率阈值
- **UpgradeRecord**：单设备在某活动中的升级记录，`lastAppliedSequence` 用于上报去重与乱序判定

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
RUNNING --(所有批次下发且全部记录终态)--> COMPLETED
CREATED/RUNNING/PAUSED --(terminate)--> TERMINATED
```

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
| POST | `/api/campaigns` | 创建分批灰度活动 |
| POST | `/api/campaigns/{id}/advance` | 推进下一批次 |
| POST | `/api/campaigns/{id}/pause` `/resume` `/terminate` | 暂停/恢复/终止 |
| POST | `/api/campaigns/{id}/devices/{deviceId}/rollback` | 回滚指定设备 |

被拒绝的操作返回 HTTP 409，body 含可区分的 `reason`（见 `RejectReason` 枚举）与 `message`。

## 本地验证

```bash
# 运行全部测试（含重复/乱序上报、并发推进、失败回滚用例，
# 日志会打印关键输入与判定依据）
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
```

## 测试覆盖

- `DeviceReportTest`：重复上报去重、乱序/迟到上报不倒退、缺失信息与平台不匹配的可区分拒绝
- `CampaignConcurrencyTest`：并发推进串行化、并发重复上报恰好生效一次、失败率超阈值自动暂停
- `RollbackAndCompatibilityTest`：失败回滚到最近确认版本、非失败记录回滚拒绝、
  平台/版本兼容约束、设备活动互斥、混合结果下的机群版本分布
