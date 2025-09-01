# XhREC

A kotlin application for automatic recording lives from StripChat.

## Usage

```plain
usage: 
 -f,--file <arg>     Room List File
    --post <arg>     Post Processor Config File (default: postprocessor.json)
 -o,--output <arg>   Output Dir
 -p,--port <arg>     Server Port [default:8090]
 -t,--tmp <arg>      Temp Dir
```

```shell
java -jar XhRec-all.jar -f list.conf -t /path/to/temp/folder -o /path/to/destnation/folder
```

### Post processing

Run processors one by one.

```json lines
{
  "default": [
    {
      "type": "fix_stamp",
      "args": [
        "D:\\Users\\My\\Documents\\XhRec\\out"
      ]
    },
    {
      "type": "slice",
      "args": [
        "30s"
      ]
    },
     // generate grid thumbnail (20x20, 400pic total)
    {
      "type": "shell",
      "noreturn": true,
      "args": [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-stats",
        "-i",
        "{{INPUT}}",
        "-vf",
         // if you change grid size, please recalculate total pic size
         // or replace '{{TOTAL_FRAMES}}/400' to 50, which means every 50 frames will be one thumbnail
        "thumbnail={{TOTAL_FRAMES}}/400,scale=200:-1,tile=20x20",
        "-vframes",
        "1",
        "{{INPUT_DIR}}\\{{FILE_NAME_NOEXT}}.thumb.png",
        "-y"
      ]
    }
  ]
}
```

## Control

There is only basic status page at `/` now. We provided a browser extension for quick control.

### Browser extension

![extension screenshot](PixPin_2025-09-01_00-31-44.png)

1. Go to the Extensions page by entering `chrome://extensions` in a new tab. (By design `chrome://` URLs are not
   linkable.)

    - Alternatively, click the Extensions menu puzzle button and select **Manage Extensions** at the bottom of the menu.
    - Or, click the Chrome menu, hover over **More Tools**, then select **Extensions**.

2. Enable Developer Mode by clicking the toggle switch next to **Developer mode**.
3. Click the **Load unpacked** button and select the `extension` directory.
   ![Extensions page 1(chrome://extensions)](PixPin_2025-09-01_00-38-56.png)
   ![Extensions page 2(chrome://extensions)](PixPin_2025-09-01_00-36-07.png)

### API

#### /add

| Parameter | Description           |
|-----------|-----------------------|
| slug      | Room/Model name       |
| quality   | Quality, default 720p |
| active    | Start auto recording  |

#### /break

Temporary stop recording

| Parameter | Description     |
|-----------|-----------------|
| slug      | Room/Model name |

#### /remove

| Parameter | Description     |
|-----------|-----------------|
| slug      | Room/Model name |

#### /activate

| Parameter | Description     |
|-----------|-----------------|
| slug      | Room/Model name |

#### /deactivate

| Parameter | Description     |
|-----------|-----------------|
| slug      | Room/Model name |

#### /quality

| Parameter | Description     |
|-----------|-----------------|
| slug      | Room/Model name |
| quality   | Quality         |

#### /list (Deprecated)

Simple json status list

##$# /status

Json status

```json lines
{
  "Model Name": {
    //total segments
    "total": 10046,
    //succeed segments
    "success": 9933,
    //failed segments
    "failed": 98,
    //total bytes
    "bytesWrite": 1409108341,
    //running segments
    "running": {
      "https://xxxx_part3.mp4": {
        // using PROXY
        "type": "PROXY",
        // start download time
        "startAt": 1756357723403
      },
      "https://xxxx_part1.mp4": {
        "type": "DIRECT",
        "startAt": 1756357716154
      }
    }
  }
}
```

#### /recorders

```json lines
[
  {
    "name": "Model Name",
    "id": 12345,
    "quality": "720p60",
     // record limit, PT2M means 2 minutes (ISO-8601 Duration format)
    "limit": "PT2M",
    // useless for now
    "lastSeen": null
  }
]
```

#### /stop-server

Finish all recording tasks.
The server won't shut down for some reason, but it's safe to kill the process when this api responds.

#### /metrics

Prometheus metrics

You can build monitor like this:
![img_2.png](img_2.png)

## Configuration

```plain
# https://zh.xhamsterlive.com/modelA q:720p limit:120
; https://zh.xhamsterlive.com/modelB q:240p
https://zh.xhamsterlive.com/modelC q:raw
```

- Start with `#` or `;` will be marked as `INACTIVE`, means will not automatically start recording.
- limit:120 means record time (in seconds).
- q:XXXX means preferred quality, raw means original quality.
  **_If not quality matches, program will select closest one._**
- `zh.` is optional, dont care about it.
