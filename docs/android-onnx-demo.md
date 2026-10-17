# Android ONNX 实测 Demo

在真机上跑通菲比的一句中文，核对声音，并记下声学模型的耗时。这一步只验证 T2S 和声码器。中文分词、RoBERTa、NPU 和模型下载不在这次范围内。

## 已完成的导出

导出目录是 `android_case/`。角色是预置中文角色 **feibi**（V2ProPlus）。合成文本是：

> 今天天气不错，我们出去走走吧。

推理时按 `Core/Inference.py` 在句首加一个句号，实际送进模型的文本是 `。今天天气不错，我们出去走走吧。`。采样率 32 kHz。当前没有安装中文 RoBERTa，`ref_bert` 和 `text_bert` 是全零。

`desktop.wav` 是桌面端原始合成。`replay.wav` 是只加载导出文件重跑的结果，两者逐样本一致，最大误差为 0。

### 模型

四个文件都是灌好 FP16 权重的自包含 FP32 ONNX，不依赖外部 `.bin`。

| 文件 | 大小 |
|---|---|
| `models/t2s_encoder_fp32.onnx` | 11 MB |
| `models/t2s_first_stage_decoder_fp32.onnx` | 293 MB |
| `models/t2s_stage_decoder_fp32.onnx` | 293 MB |
| `models/vits_fp32.onnx` | 239 MB |

### 数组

`manifest.json` 记录每个数组的文件名、dtype 和 shape。

| 名字 | dtype | shape | 作用 |
|---|---|---|---|
| `ref_seq` | int64 | `[1, 42]` | 参考句音素 |
| `ref_bert` | float32 | `[42, 1024]` | 参考句 BERT，当前全零 |
| `text_seq` | int64 | `[1, 29]` | 目标句音素 |
| `text_bert` | float32 | `[29, 1024]` | 目标句 BERT，当前全零 |
| `ssl_content` | float32 | `[1, 768, 231]` | 参考音频的 HuBERT 特征 |
| `ge` | float32 | `[1, 1024, 1]` | V2ProPlus 音色向量 |
| `ge_advanced` | float32 | `[1, 512, 1]` | V2ProPlus 音色向量 |

## 手机应用

工程在 `android/onnx-demo`，包名 `com.genie.onnxdemo`，只保留 `arm64-v8a`。依赖是 `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`。

没有电脑时，用手机浏览器打开安装包并安装，安装时允许未知来源：

https://github.com/lostprobe99/Genie-TTS/raw/cursor/android-onnx-demo-doc-3875/android/onnx-demo/genie-onnx-demo.apk

测试数据是单独的一个压缩包 `feibi-case.zip`，不打进安装包。下载完成后，在应用里点「导入」并选中这个文件。应用把它解压到自己的目录。解压完成后连点两次「合成」，第二次的时间才算数。

换句子时不用重新下载模型。下面四个小包只带新的音素和全零韵律特征，导入后会盖住原来的 `text_seq`、`text_bert` 和 `manifest.json`。屏幕上的句子来自包里的 `text`。

- `android/onnx-demo/sentences/01-qiyan.zip`：朝辞白帝彩云间，千里江陵一日还。
- `android/onnx-demo/sentences/02-luoshen.zip`：远而望之，皎若太阳升朝霞。迫而察之，灼若芙蕖出渌波。
- `android/onnx-demo/sentences/03-lun-chijiuzhan.zip`：战争的伟力之最深厚的根源，存在于民众之中。

如果手机里已经有解压好的 `android_case`，点「选择已解压的目录」，选中包含 `manifest.json` 的那一层。Android 11 以后，文件管理器写不进 `Android/data`，所以应用会把选中的文件复制进自己的目录。应用内的「下载测试数据」仍可使用。

在装好 Android SDK 的电脑上也可以自己编译：

```bash
cd android/onnx-demo
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push android_case /sdcard/Android/data/com.genie.onnxdemo/files/
```

模型不要打进 APK。界面上有一个「合成」按钮。按下后：

1. 按 `manifest.json` 读入七个数组。
2. 依次运行 encoder、first-stage decoder、stage decoder、VITS。stage decoder 一步一个语义 token，直到停止标志为真，最多 500 步。停止条件和张量顺序与 `Core/Inference.py` 的 `t2s_cpu` 一致。
3. VITS 输入是 `text_seq`、预测出的语义 token、`ge`、`ge_advanced`。
4. 用 `AudioTrack` 播放 32 kHz、单声道、float PCM。

执行放在后台线程。ONNX Runtime 的 Java 张量用完即关。先只使用 `CPUExecutionProvider`。线程数设成大核数量，一般是 4 到 6。

日志分开记：

- 模型加载时间
- encoder 时间
- decoder 步数和总时间
- 声码器时间
- 端到端时间
- 音频时长
- 实时率 = 计算时间 / 音频时长

第一次运行包含图优化。和桌面端对比、以及记录耗时，都以第二次为准。

## 通过标准

手机播放或保存的声音与 `android_case/desktop.wav` 一致。第二次运行的分段时间已记下来。

## 先不做

- 在手机上重写中文分词、多音字和拼音
- 下载或运行中文 RoBERTa
- 把 stage decoder 放到 NPU
- 角色模型下载和多角色缓存
