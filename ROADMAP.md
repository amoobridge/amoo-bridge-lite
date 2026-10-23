# 路线图（对外分发版）

## 已完成

- 能力收敛：界面点按 / 截图 / 界面取词实现删除，接口保留返回 NOT_YET
- 系统层同步关闭手势与截图权限，仅保留界面结构读取
- 安全：随机端口、令牌轮换 + 私有存储、配对码、请求签名、审计、exec 白名单、通知采集默认关
- 数据隔离：/sdcard/stepbridge-data，与主桥互不干扰
- 界面：状态 / 权限体检 / 配对 / 工具清单 / 指标 / 审计 / 诊断包
- 工程化：README / SECURITY / LICENSE / CHANGELOG / TOOLCHAIN / build.sh / pack.sh

## 暂不提供

| 能力 | 现状 |
|---|---|
| 界面点按 / 长按 / 滑动 / 按文字点击 | 无实现，返回 NOT_YET |
| 截图 / 截图回传 / 视觉兜底 | 无实现，返回 NOT_YET |
| 界面取词 | 文件已移除 |

策略：暂不开源，等安卓底层跟进。

## 未完成的安全项

- [ ] MANAGE_EXTERNAL_STORAGE 降级为 SAF + MediaStore
- [ ] 令牌改为 Keystore 派生
