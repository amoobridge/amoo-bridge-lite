# StepBridge（对外分发版）

让 AI 助手在**用户本人授权的 Android 设备**上，用系统公开接口完成文件、应用、界面状态、通知四类操作。

- 不修改任何第三方应用，不注入进程，不要求 root，不申请设备管理员
- 所有权限都对应系统里一个用户可见、可随时关闭的开关
- 数据只落在设备本地，不上传任何服务器

| 项 | 值 |
|---|---|
| 包名 | com.step.bridge |
| 当前版本 | 2.3-lite.4（versionCode 16） |
| 数据目录 | /sdcard/stepbridge-data |

## 下载

apk 在 [Releases](https://github.com/amoobridge/amoo-bridge-lite/releases)，同时提供 sha256 校验文件：

```bash
sha256sum -c stepbridge-2.3-lite.apk.sha256
```

## 能力范围

可用：文件、应用、界面结构读取、通知、配置与审计，共 34 项工具。

不可用：界面点按、长按、滑动、截图回传、界面取词、视觉兜底。接口保留，调用返回 `code=NOT_YET`；实现已从本版移除，系统层也同步关闭了手势与截图权限。

本仓库是 lite 版的完整源码，Apache-2.0 授权。完整版暂不公开，等安卓底层跟进。

## 为什么是桥而不是 MCP

MCP 适合服务端与工具之间的调用。手机上的场景是助手与一台有 GUI、有通知、有文件的真机之间，多一层协议反而拖慢。这里直接做成一次 HTTP 调用一个工具，参数与返回都是扁平 JSON。

## 架构

| 组件 | 类型 | 职责 |
|---|---|---|
| BridgeService | 前台 Service（dataSync） | 常驻，前台通知可见可停 |
| HttpBridge | 内置 HTTP | 仅监听 127.0.0.1，Bearer 令牌 |
| UiService | AccessibilityService | 控件树 dump；手势与截图接口保留，返回 NOT_YET |
| WxNotify | NotificationListenerService | 通知落盘为 JSONL（默认关闭） |
| BootReceiver / WatchdogReceiver | 广播 | 开机自启、异常自恢复 |

调用链：`POST /call → Tools.dispatch → 具体能力 → JSON`

## 接口

```
POST http://127.0.0.1:<port>/call
Authorization: Bearer <token>
{"tool":"ui_dump_raw","args":{}}
```

- 端口：默认 `0`，由系统随机分配；实际端口写入 `status.json` 的 `http_port`
- 令牌：每次启动轮换，主副本存应用私有目录；`exportToken=false` 时不导出
- 探活：`GET /ping`、`GET /status`
- 配对：`POST /pair` + `{"code":"123456"}`（App 内生成，120 秒有效）换回令牌
- 签名：`X-Ts`（毫秒）+ `X-Sig` = HMAC-SHA256(token, ts + 换行 + body)，时间窗 ±60 秒，`requireSign=true` 时强制

> **本地回环不等于安全**：Android 上 `127.0.0.1` 对同设备所有应用可达。令牌必须保留，且建议后续改为配对流程（二维码）或请求签名。

## 配置

`config.json`（应用私有目录）默认值按最小权限设定：

```json
{
  "port": 0,
  "allowExec": false,
  "execWhitelist": ["ls","cat","df","wc","date","id","stat"],
  "notifyCollect": false,
  "exportToken": true,
  "audit": true
}
```

- `allowExec` / `execWhitelist`：命令执行默认关闭，开启后仅放行白名单首词
- `notifyCollect`：通知采集默认关闭，开启后才落盘
- `audit`：审计日志 `audit.jsonl`，记录时间、工具名、成败

运行期修改：`{"tool":"config_set","args":{"config":{"allowExec":false}}}`

## 工具

`Tools.NAMES` 共 34 项：

| 组 | 工具 |
|---|---|
| 基础 | `ping` `info` |
| 文件 | `exec` `ls` `read` `write` `mkdir` `rm` `mv` `cp` `stat` |
| 应用 | `start_app` `open_url` `share_text` `list_apps` |
| 系统 | `clipboard` `toast` `notify` `perm` `battery` `volume` `brightness` `vibrate` |
| 界面 | `ui_dump` `ui_dump_raw` `ui_back` `ui_home` `ui_recents` |
| 通知 | `notify_status` `open_notify_access` `battery_allow` |
| 配置 | `config_get` `config_set` `audit_tail` |

保留但未实现的 8 个名字，调用一律返回 `NOT_YET`：`ui_tap` `ui_swipe` `ui_click` `ui_longpress` `shot` `shot_b64` `wx_copy` `wx_multi_copy`。

兼容别名：`shell_exec` → `exec`、`file_list` → `ls`、`file_read` → `read`、`file_write` → `write`、`sys_info` → `info`。

## 权限

| 权限 | 用途 | 授权入口 |
|---|---|---|
| BIND_ACCESSIBILITY_SERVICE | 读界面结构 | 设置 → 无障碍 |
| BIND_NOTIFICATION_LISTENER_SERVICE | 读通知标题与正文 | 设置 → 通知使用权 |
| MANAGE_EXTERNAL_STORAGE | 文件读写 | 设置 → 特殊应用权限 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 降低休眠冻结 | 系统对话框 |
| POST_NOTIFICATIONS | 前台服务提示 | 运行时 |

## 构建

```bash
dev pull jdk android-tools android-sdk   # 首次拉工具链
dev pull pylib                           # 需要 Pillow/numpy 时
source /root/run/env.sh
bash build.sh                            # 产物在 dist/
```

热区/冷区的完整说明（目录、dev 命令、Python 与 Java 环境、常见坑）见 [TOOLCHAIN.md](TOOLCHAIN.md)。

一句话版本：冷仓 `/sdcard/dev` 存工具链压缩包，热区 `/root/run` 展开使用，根分区只有 1.9G，用完 `dev drop`。

## 边界（明确不做）

- 不做进程注入、hook、动态调试第三方应用
- 不做应用重打包与签名替换
- 不绕过第三方应用自身的安全设置
- 不用界面点按代替用户完成支付、删除、发送、好友确认等不可逆动作

## 相关文档

| 文档 | 内容 |
|---|---|
| [CHANGELOG.md](CHANGELOG.md) | 各版本改动 |
| [ROADMAP.md](ROADMAP.md) | 已完成与后续计划 |
| [SECURITY.md](SECURITY.md) | 威胁模型与缓解措施 |
| [TOOLCHAIN.md](TOOLCHAIN.md) | 构建环境，冷热分区 |
| [开发者建议.md](开发者建议.md) | 这个桥为什么存在 |

## 免责

本软件用于个人设备上的效率与无障碍辅助。使用者需自行确保其使用方式符合当地法律与各应用的用户协议；作者不对由此产生的账号或法律风险负责。

## 许可证

Apache License 2.0，全文见 LICENSE；分发时请保留 NOTICE，修改过的文件请标注改动。
