# XhREC
A kotlin application for automatic recording lives from StripChat.

# Usage

```plain
usage: CommandLineParameters
 -f,--file <arg>     Room List File
 -o,--output <arg>   Output Dir
 -p,--port <arg>     Server Port [default:8090]
 -t,--tmp <arg>      Temp Dir
```
## Example
```shell
java -jar XhRec-all.jar -f list.conf -t /path/to/temp/folder -o /path/to/destnation/folder
```

# Configuration

```plain
# https://zh.xhamsterlive.com/modelA q:720p
; https://zh.xhamsterlive.com/modelB q:240p
https://zh.xhamsterlive.com/modelC q:raw
```
+ Start with `#` or `;` will be marked as `INACTIVE`, means will not automatically start recording
+ q:XXXX means preferred quality, raw means original quality. ***If not quality matches, program will select closest one.***
+ `zh.` is optional, dont care about it.
+ 
