# XhREC

基于 Kotlin 的直播自动录制工具，配合浏览器扩展实现一键控制。

## 快速开始

```shell
./gradlew build
java -jar build/libs/XhRec-all.jar
```

启动后打开 `https://localhost:8090` 进入控制面板。

## 命令行参数

| 参数 | 说明 | 默认值 |
|---|---|---|
| `-f`, `--file` | 房间列表配置 | `list.conf` |
| `-o`, `--output` | 输出目录 | `out` |
| `-t`, `--tmp` | 临时目录 | `tmp` |
| `-p`, `--port` | HTTP 服务端口 | `8090` |
| `-u`, `--users` | 用户文件 | `users.txt` |
| `-post` | 后处理配置 | `postprocessor.json` |

```shell
java -jar build/libs/XhRec-all.jar -p 12340 -f list.conf -post postprocessor.json -t /tmp/xhrec -o /out
```

## 配置说明

### list.conf

每行一个房间。以 `#` 或 `;` 开头的行视为停用（不会自动开始录制）。

```ini
# https://stripchat.com/modelA q:720p limit:120
; https://stripchat.com/modelB q:240p
https://stripchat.com/modelC q:highest
```

| 字段 | 说明 |
|---|---|
| `q:<画质>` | 首选画质：`240p`、`480p`、`720p`、`720p60`、`1080p`、`1080p60` 或 `highest`（默认）。`raw` 已废弃，请使用 `highest` |
| `limit:<秒>` | 录制时长限制（秒） |
| `size:<大小>` | 录制大小限制（支持后缀：`K`、`M`、`G`，如 `500M`） |
| `autopay` | 开启自动付费（用于私密秀） |
| `pkey:<key>` | 自定义解密密钥 |

若请求的画质不可用，程序会自动选择最接近的画质。

### xhrec.json

流解密密钥存储。首次运行时自动创建默认配置。

```json
{
  "streamAuthKey": "默认密钥名，从 master playlist 提取失败时使用",
  "decryptKeys": {
    "密钥名 1": "解密密钥",
    "密钥名 2": "解密密钥"
  }
}
```

| 字段 | 说明 |
|---|---|
| `streamAuthKey` | 默认流鉴权密钥名 |
| `decryptKeys` | 解密密钥键值对（密钥名 → 密钥） |

### users.txt

用于自动付费的用户 Cookie。每行一个，支持 `#` 和 `;` 注释。

启动时每个 Cookie 会向平台 API 验证，获取用户 ID、名称和金币余额。当私密秀需要付费时，系统会自动选择余额充足的用户。

```
# 注释行
cookie_string_here
```

## 控制面板与浏览器扩展

### 控制面板

`https://localhost:8090` — 管理房间、查看实时状态、控制录制。

![控制面板](image.png)

### 用户脚本

[安装地址](https://greasyfork.org/zh-CN/scripts/582444-xhrec-control-panel)

![用户脚本截图](image-1.png)

## API 参考

除特别说明外，所有接口均返回 JSON。参数通过 Query String 传递。

### 房间管理

| 接口 | 参数 | 说明 |
|---|---|---|
| `/add` | `name`, `quality`, `active`, `limit`, `autopay`, `pkey`, `size` | 添加房间 |
| `/remove` | `id` | 移除房间 |
| `/start` | `id` | 开始录制 |
| `/stop` | `id` | 停止录制 |
| `/restart` | `id` | 停止后重新开始录制 |
| `/break` | `id` | 临时停止（下次轮询时恢复） |

### 房间设置

| 接口 | 参数 | 说明 |
|---|---|---|
| `/activate` | `id` | 启用自动录制 |
| `/deactivate` | `id` | 停用自动录制 |
| `/quality` | `id`, `q` | 设置画质 |
| `/autopay` | `id`, `v` (true/false) | 开关自动付费 |
| `/limit` | `id`, `v` (秒) | 设置时长限制（0 = 无限制） |
| `/sizelimit` | `id`, `v` | 设置大小限制（0 = 无限制） |

### 状态与监控

| 接口 | 说明 |
|---|---|
| `/status` | 活跃房间状态（分段数、字节数、正在下载） |
| `/list` | 所有房间的状态、会话、画质 |
| `/dashboard` | 聚合数据：房间、状态、列表、指标 |
| `/metrics` | Prometheus 指标接口 |

### 实时预览

| 接口 | 参数 | 说明 |
|---|---|---|
| `/mse/live` | `id` | 正在录制的 MP4 流 |

### 服务器控制

| 接口 | 说明 |
|---|---|
| `/graceful-stop` | 完成录制后关闭服务 |
| `/stop-server` | 完成录制和后处理后退出 |

### Status 返回格式

```json
{
  "Model Name": {
    "total": 10046,
    "success": 9933,
    "failed": 98,
    "bytesWrite": 1409108341,
    "running": {
      "https://...part3.mp4": {
        "type": "PROXY",
        "startAt": 1756357723403
      }
    }
  }
}
```

## 后处理

在 `postprocessor.json` 中定义。录制结束后按顺序依次执行。

### 内置处理器

| 类型 | 说明 |
|---|---|
| `fix_stamp` | 修复 MP4 时间戳 |
| `move` | 移动/重命名输出文件 |
| `slice` | 按时间段切割视频 |
| `shell` | 执行任意 Shell 命令 |

### 模板变量

在 `move` 的 `dest` 和 `shell` 的 `args` 中均可使用：

| 变量 | 说明 |
|---|---|
| `{{ROOM_NAME}}` | 房间/主播名称 |
| `{{ROOM_ID}}` | 房间 ID |
| `{{RECORD_START}}` | 录制开始时间（格式化） |
| `{{RECORD_END}}` | 录制结束时间（格式化） |
| `{{RECORD_DURATION}}` | 录制时长（秒） |
| `{{RECORD_DURATION_STR}}` | 录制时长（如 `00h01m30s`） |
| `{{INPUT}}` | 输入文件路径 |
| `{{INPUT_DIR}}` | 输入文件目录 |
| `{{FILE_NAME}}` | 输入文件名 |
| `{{FILE_NAME_NOEXT}}` | 输入文件名（无扩展名） |
| `{{TOTAL_FRAMES}}` | 精确总帧数 |
| `{{TOTAL_FRAMES_GUESS}}` | 估算总帧数（FPS × 时长） |

### 配置示例

```json
{
  "default": [
    { "type": "fix_stamp", "output": "out" },
    {
      "type": "move",
      "dest": "out/[{{ROOM_ID}}]{{ROOM_NAME}}@{{RECORD_START}}-{{RECORD_END}} {{RECORD_DURATION_STR}}",
      "date_pattern": "yyyy-MM-dd HH:mm:ss"
    },
    { "type": "slice", "duration": "1m10s" },
    {
      "type": "shell",
      "noreturn": true,
      "remove_input": false,
      "args": [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-stats",
        "-i", "{{INPUT}}",
        "-vf", "thumbnail={{TOTAL_FRAMES_GUESS}}/400,scale=200:-1,tile=20x20",
        "-vframes", "1",
        "{{INPUT_DIR}}/{{FILE_NAME_NOEXT}}.thumb.png",
        "-y"
      ]
    }
  ]
}
```

## 日志与监控

日志写入 `./logs` 目录，按天滚动（`xhrec.yyyy-MM-dd.log`）。

Prometheus 指标通过 `/metrics` 接口暴露。Grafana 仪表盘示例：

![grafana](img_1.png)
