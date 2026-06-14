# XhREC

Kotlin 直播自动录制工具，配合浏览器扩展实现一键控制。

## 快速开始

```shell
./gradlew build
java -jar build/libs/XhRec-all.jar
```

然后打开 `https://localhost:8090` 进入控制面板。

## CLI 参数

| 参数               | 说明         | 默认值                  |
|------------------|------------|----------------------|
| `-f`, `--file`   | 房间列表配置     | `list.conf`          |
| `-o`, `--output` | 输出目录       | `out`                |
| `-t`, `--tmp`    | 临时目录       | `tmp`                |
| `-p`, `--port`   | HTTP 服务器端口 | `8090`               |
| `-u`, `--users`  | 用户文件       | `users.txt`          |
| `-post`          | 后处理器配置     | `postprocessor.json` |

```shell
java -jar build/libs/XhRec-all.jar -p 12340 -f list.conf -post postprocessor.json -t /tmp/xhrec -o /out
```

## 配置

### list.conf

每行一个房间。以 `#` 或 `;` 开头的行表示未启用（不会自动录制）。

```ini
# https://stripchat.com/modelA q:720p limit:120
; https://stripchat.com/modelB q:240p
https://stripchat.com/modelC q:highest
```

| 字段             | 说明                                                                                           |
|----------------|----------------------------------------------------------------------------------------------|
| `q:<quality>`  | 偏好画质：`240p`、`480p`、`720p`、`720p60`、`1080p`、`1080p60` 或 `highest`（默认）。`raw` 已弃用，请使用 `highest` |
| `limit:<sec>`  | 录制时长限制（秒）                                                                                    |
| `size:<bytes>` | 录制大小限制（支持后缀：`K`、`M`、`G`，如 `500M`）                                                            |
| `autopay`      | 为付费秀启用自动支付                                                                                   |
| `pkey:<key>`   | 自定义 psch 密钥                                                                                  |

如果请求的画质不可用，将自动选择最接近的匹配。

### xhrec.json

流解密密钥存储，首次运行时会创建默认文件。

```json
{
  "streamAuthKey": "默认 psch 密钥，从 master playlist 提取失败时使用",
  "decryptKeys": {
    "psch key 1": "decrypt key",
    "psch key 2": "decrypt key"
  }
}
```

| 字段              | 说明                      |
|-----------------|-------------------------|
| `streamAuthKey` | 默认流认证密钥                 |
| `decryptKeys`   | 解密密钥映射（psch key → 解密密钥） |

### users.txt

用于自动支付的用户 Cookie。每行一个 Cookie，以 `#` 或 `;` 开头的行将被忽略。

每个 Cookie 在启动时通过平台 API 验证，解析出用户 ID、名称和金币余额。当付费秀需要支付时，系统会选择金币充足的用户。

```
# 可选注释
cookie_string_here
```

## WebUI & 浏览器扩展

### 控制面板

`https://localhost:8090` — 管理房间、查看实时状态、控制录制。

![dashboard](image.png)

### UserScript

[安装](https://greasyfork.org/zh-CN/scripts/582444-xhrec-control-panel)
![user script](image-1.png)

## API 参考

所有接口均返回 JSON（除非另有说明）。参数通过查询字符串传递。

### 房间管理

| 接口         | 参数                                                              | 说明            |
|------------|-----------------------------------------------------------------|---------------|
| `/add`     | `name`, `quality`, `active`, `limit`, `autopay`, `pkey`, `size` | 添加房间          |
| `/remove`  | `id`                                                            | 移除房间          |
| `/start`   | `id`                                                            | 开始录制          |
| `/stop`    | `id`                                                            | 停止录制          |
| `/restart` | `id`                                                            | 停止后重新开始录制     |
| `/break`   | `id`                                                            | 暂停录制（下次轮询时恢复） |

### 房间设置

| 接口            | 参数                    | 说明              |
|---------------|-----------------------|-----------------|
| `/activate`   | `id`                  | 启用自动录制          |
| `/deactivate` | `id`                  | 禁用自动录制          |
| `/quality`    | `id`, `q`             | 设置画质            |
| `/autopay`    | `id`, `v`（true/false） | 切换自动支付          |
| `/limit`      | `id`, `v`（秒）          | 设置时长限制（0 = 无限制） |
| `/sizelimit`  | `id`, `v`             | 设置大小限制（0 = 无限制） |

### 状态与监控

| 接口           | 说明                   |
|--------------|----------------------|
| `/status`    | 活跃房间状态（片段数、字节数、正在下载） |
| `/list`      | 所有房间（含状态、会话状态、画质）    |
| `/dashboard` | 合并数据：房间、状态、列表、指标     |
| `/metrics`   | Prometheus 指标接口      |

### 实时预览

| 接口          | 参数   | 说明       |
|-------------|------|----------|
| `/mse/live` | `id` | 正在录制的视频流 |

### 服务器控制

| 接口               | 说明          |
|------------------|-------------|
| `/graceful-stop` | 完成录制后关闭     |
| `/stop-server`   | 完成录制和后处理后退出 |

### 状态响应格式

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

在 `postprocessor.json` 中定义，录制完成后按顺序执行。

### 内置处理器

| 类型          | 说明         |
|-------------|------------|
| `fix_stamp` | 修复 MP4 时间戳 |
| `move`      | 移动/重命名输出文件 |
| `slice`     | 将视频分割为片段   |
| `shell`     | 执行任意外部命令   |

### 模板变量

可用于 `move` 输出目标和 `shell` 命令参数：

| 变量                        | 说明               |
|---------------------------|------------------|
| `{{ROOM_NAME}}`           | 主播/房间名           |
| `{{ROOM_ID}}`             | 房间 ID            |
| `{{RECORD_START}}`        | 格式化的开始时间         |
| `{{RECORD_END}}`          | 格式化的结束时间         |
| `{{RECORD_DURATION}}`     | 时长（秒）            |
| `{{RECORD_DURATION_STR}}` | 时长格式 `00h01m30s` |
| `{{RECORD_QUALITY}}`      | 画质               |
| `{{INPUT_ABS}}`           | 输入文件路径           |
| `{{INPUT_DIR}}`           | 输入文件目录           |
| `{{INPUT_NAME}}`          | 输入文件名            |
| `{{INPUT_NAME_NOEXT}}`    | 文件名（无扩展名）        |
| `{{TOTAL_FRAMES}}`        | 精确帧数             |
| `{{TOTAL_FRAMES_GUESS}}`  | 估算帧数（FPS × 时长）   |

### 配置示例

```json
{
  "default": [
    {
      "type": "fix_stamp",
      "output": "out"
    },
    {
      "type": "move",
      "output": "out/[{{ROOM_ID}}]{{ROOM_NAME}}@{{RECORD_START}}-{{RECORD_END}} {{RECORD_DURATION_STR}}",
      "date_pattern": "yyyy-MM-dd HH:mm:ss"
    },
    {
      "type": "slice",
      "output": "out",
      "duration": "1m10s"
    },
    {
      "type": "shell",
      "noreturn": true,
      "remove_input": false,
      "date_pattern": "yyyy-MM-dd_HH-mm-ss",
      "cmd": [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-stats",
        "-i",
        "{{INPUT_ABS}}",
        "-vf",
        "thumbnail={{TOTAL_FRAMES_GUESS}}/400,scale=200:-1,tile=20x20",
        "-vframes",
        "1",
        "{{INPUT_DIR}}/{{INPUT_NAME_NOEXT}}.thumb.png",
        "-y"
      ]
    }
  ]
}
```

## 日志与监控

日志写入 `./logs` 目录，按日滚动（`xhrec.yyyy-MM-dd.log`）。

Prometheus 指标暴露在 `/metrics` 接口。Grafana 面板示例：

![grafana](img_1.png)
