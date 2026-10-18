# Android ONNX 实测 Demo

在真机上跑通菲比。输入框里打一句中文，点「合成」，手机自己把字变成音素，再用中文 RoBERTa 填 `text_bert`，然后交给 T2S 和声码器。参考音色仍用已经放在手机里的特征。`ref_bert` 仍是全零。NPU 不在这次范围内。

## 已完成的导出

导出目录是 `android_case/`。角色是预置中文角色 **feibi**（V2ProPlus）。合成文本是：

> 今天天气不错，我们出去走走吧。

推理时按 `Core/Inference.py` 在句首加一个句号，实际送进模型的文本是 `。今天天气不错，我们出去走走吧。`。采样率 32 kHz。参考句 `ref_bert` 仍是全零。目标句 `text_bert` 由手机上的 RoBERTa 现算；模型还没下载时，这块保持全零，合成仍能出声。

`desktop.wav` 是桌面端原始合成。`replay.wav` 是只加载导出文件重跑的结果，两者逐样本一致，最大误差为 0。

### 模型

四个声学文件都是灌好 FP16 权重的自包含 FP32 ONNX，不依赖外部 `.bin`。

| 文件 | 大小 |
|---|---|
| `models/t2s_encoder_fp32.onnx` | 11 MB |
| `models/t2s_first_stage_decoder_fp32.onnx` | 293 MB |
| `models/t2s_stage_decoder_fp32.onnx` | 293 MB |
| `models/vits_fp32.onnx` | 239 MB |

韵律模型是 `roberta/model_fp16.onnx`，599,034,176 字节。权重是 FP16，输出仍是 float32，形状 `[token 数, 1024]`。分词表打进安装包。仓库里按约 45 MB 一片放在 `roberta/chunks/`，说明在 `roberta/parts.json`。手机上拼好以后，文件大小已经对上的不会再下。声学模型不用为了韵律重新下载。

### 数组

`manifest.json` 记录每个数组的文件名、dtype 和 shape。

| 名字 | dtype | shape | 作用 |
|---|---|---|---|
| `ref_seq` | int64 | `[1, 42]` | 参考句音素 |
| `ref_bert` | float32 | `[42, 1024]` | 参考句 BERT，当前全零 |
| `text_seq` | int64 | `[1, 29]` | 天气这句的音素。应用里改由输入框现算，长度随句子变化 |
| `text_bert` | float32 | `[29, 1024]` | 目标句 BERT。应用里按音素个数现算；有韵律模型时来自 RoBERTa，否则全零 |
| `ssl_content` | float32 | `[1, 768, 231]` | 参考音频的 HuBERT 特征 |
| `ge` | float32 | `[1, 1024, 1]` | V2ProPlus 音色向量 |
| `ge_advanced` | float32 | `[1, 512, 1]` | V2ProPlus 音色向量 |

## 手机应用

工程在 `android/onnx-demo`，包名 `com.genie.onnxdemo`，只保留 `arm64-v8a`。当前安装包版本是 1.10。依赖是 `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`。

没有电脑时，用手机浏览器打开安装包并安装，安装时允许未知来源：

https://github.com/lostprobe99/Genie-TTS/raw/cursor/android-onnx-demo-doc-3875/android/onnx-demo/genie-onnx-demo.apk

测试数据是单独的一个压缩包 `feibi-case.zip`，不打进安装包。下载完成后，在应用里点「导入」并选中这个文件。应用把它解压到自己的目录。解压完成后连点两次「合成」，第二次的时间才算数。如果点「合成」后应用退出，重新打开，日志里会写上次停在哪一步。

已经装过以前的版本、声学模型还在的话，不用重新导入测试数据。韵律模型有 572 MB，GitHub 单文件上限是 100 MB，所以不能打成一个压缩包。它拆成 13 个约 45 MB 的文件，放在 `android_case/roberta/chunks/`。用浏览器逐个下载，文件名是 `model_fp16.onnx.00` 到 `model_fp16.onnx.12`。下载完成后，在应用里点「导入韵律模型」，把这 13 个文件一次选中。也可以点「选择已解压的目录」，选中放着这些分片的文件夹。拼好以后大小是 599,034,176 字节。声学模型不会因此重下。

分片地址：

https://github.com/lostprobe99/Genie-TTS/tree/cursor/android-onnx-demo-doc-3875/android_case/roberta/chunks

换句子直接改输入框。应用会在句首加一个句号，和 `Core/Inference.py` 一样，然后在手机上做分词、多音字、变调和儿化。日志里会写出归一化文本、音素个数和音素。下面三句已经和桌面前端对过，音素个数应是 31、49、41。「一日还」是 `h ai2`，「朝霞」是 `zh ao1 x ia2`。

- 朝辞白帝彩云间，千里江陵一日还。
- 远而望之，皎若太阳升朝霞。迫而察之，灼若芙蕖出渌波。
- 战争的伟力之最深厚的根源，存在于民众之中。

天气那句是 29 个音素：今天天气不错，我们出去走走吧。

韵律模型在的时候，日志里会多一行「韵律 N ms」。连点两次「合成」，第二次才把加载时间摘出去。第一次会把韵律模型读进内存，并一直留着，下次点「合成」不用再读。如果日志写「韵律模型还没放进来」，就是还没下完，这时 `text_bert` 仍为零。如果写「韵律对齐不上」，这句话没有填上韵律，也退回全零，不会闪退。

如果手机里已经有解压好的 `android_case`，点「选择已解压的目录」，选中包含 `manifest.json` 的那一层。Android 11 以后，文件管理器写不进 `Android/data`，所以应用会把选中的文件复制进自己的目录。应用内的「下载测试数据」仍可使用。

在装好 Android SDK 的电脑上也可以自己编译：

```bash
cd android/onnx-demo
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push android_case /sdcard/Android/data/com.genie.onnxdemo/files/
```

声学模型和韵律模型都不要打进 APK。中文前端的词典、拼音表和 RoBERTa 分词表打进安装包。界面上有输入框、「导入韵律模型」和「合成」。按下「合成」后：

1. 按 `manifest.json` 读入参考音色：`ref_seq`、`ref_bert`、`ssl_content`、`ge`、`ge_advanced`。
2. 用输入框里的字现算 `text_seq`。韵律模型在的话，用归一化后的句子跑 RoBERTa，按每个字对应的音素个数把 1024 维特征铺开，得到 `text_bert`。模型不在，或对不齐，就造一块全零的。
3. 依次运行 encoder、first-stage decoder、stage decoder、VITS。stage decoder 一步一个语义 token，直到停止标志为真，最多 500 步。停止条件和张量顺序与 `Core/Inference.py` 的 `t2s_cpu` 一致。
4. VITS 输入是 `text_seq`、预测出的语义 token、`ge`、`ge_advanced`。
5. 用 `AudioTrack` 播放 32 kHz、单声道、float PCM。

执行放在后台线程。解码循环把上一步的输出张量直接送给下一步，不再每步拷贝 48 块缓存。声码器优先使用 XNNPACK，打不开时退回 CPU。文本编码、两段解码和韵律模型仍用 CPU。线程数设成大核数量，一般是 4 到 6。

日志分开记：

- 前端加载时间（只记第一次）
- 归一化文本、音素个数和音素
- 韵律模型加载时间（只记第一次）
- 韵律计算时间
- 声学模型加载时间
- encoder 时间
- decoder 步数和总时间
- 声码器时间
- 端到端时间
- 音频时长
- 实时率 = 计算时间 / 音频时长

第一次运行包含图优化和把韵律模型读进内存。和桌面端对比、以及记录耗时，都以第二次为准。

## 通过标准

输入已经听过的三句，日志里的音素个数是 31、49、41，音素与桌面前端一致。「一日还」是 `h ai2`，「朝霞」是 `zh ao1 x ia2`。韵律模型下好以后，这三句的日志里有「韵律 N ms」，不再写 text_bert 仍为零。新句子直接打字。第二次运行的分段时间仍以日志为准。解码采样有随机性，同一句的步数和时长可以不同。

## 先不做

- 把 stage decoder 放到 NPU
- 角色模型下载和多角色缓存
- 用 RoBERTa 重算参考句的 `ref_bert`
