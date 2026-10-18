# 给下一个 Agent 的交接

先读完这份再改代码。用户用中文沟通，只看手机。详细的已上线行为在 `docs/android-onnx-demo.md`。这份只交代目标、已经定下来的结论、下一步怎么做。

## 最终效果

手机上安装一个调试包，输入中文（含长文），听到选定角色的声音。

已经完成的是中文角色菲比（feibi，GPT-SoVITS V2ProPlus）：

- 手机自己做中文前端，得到音素。
- 手机自己用中文 RoBERTa 填目标句 `text_bert` 和参考句 `ref_bert`。
- 声学模型在手机上跑 encoder、自回归 decoder、声码器，播出 32 kHz 单声道。
- 长文按原仓库 `TextSplitter` 切开，一句合成完就播放，再合下一句。

下一步是再加一个中文角色，在应用里切换音色。中文前端和 RoBERTa 共用。每个角色单独带四份声学模型、参考录音特征和一句参考原文。菲比留在手机里，不要覆盖掉。

做完之后，用户覆盖安装同一个包，不用卸载，不用重下已经在手机里的菲比声学模型和韵律模型。点一下换成新角色，再点一次换回菲比。长文仍然逐句播放。

## 用户和沟通

- 始终用中文回复。
- 用户没有安卓开发机，只有自己的 arm64 手机。安装包必须是可下载的 debug APK。不要让用户自己编译，不要让用户用 adb。
- 不要每改一点就 push。用户会收到邮件，明确说过很烦。一个能在手机上用的结果再提交、推送一次、更新一次 PR。
- 没有明确要求改文件时，不要改仓库。这次交接文档是用户明确要求写的。
- 手机上已经下好的测试数据和韵律模型不能要求重新下载。下载逻辑必须跳过「本地长度已经等于预期大小」的文件。
- 不要把大文件拆成十几个包让用户手动下载再导入。用户拒绝过这件事。应用内自动下载，或一个压缩包导入。
- 安装方式是覆盖安装。卸载会清掉 `filesDir`。包名 `com.genie.onnxdemo`，minSdk 26，只保留 `arm64-v8a`。
- 模型目录是 `filesDir/android_case`，真机上是 `/data/user/0/com.genie.onnxdemo/files/android_case`。Android 11 以后文件管理器写不进 `Android/data`。
- 计时看第二次运行。第一次含模型加载。`TtsEngine` 的「总计」不含韵律毫秒。实时率 = 总计毫秒 / 音频时长。解码步数来回变是采样，不是 bug。
- 应用若中途退出，让用户重新打开，把日志第一行「上次没有正常结束：…」发过来。来源是 `filesDir/last_step.txt`。

## 现在的版本

| 项 | 值 |
|---|---|
| 仓库 | `lostprobe99/Genie-TTS`（公开） |
| 分支 | `cursor/android-onnx-demo-doc-3875` |
| 基线 | `master` |
| PR | https://github.com/lostprobe99/Genie-TTS/pull/1 |
| 最新提交 | 以这条分支的 HEAD 为准 |
| 安装包 | versionName **1.14**，versionCode **15** |
| APK | https://github.com/lostprobe99/Genie-TTS/raw/cursor/android-onnx-demo-doc-3875/android/onnx-demo/genie-onnx-demo.apk |
| 测试数据 raw 根 | `https://raw.githubusercontent.com/lostprobe99/Genie-TTS/cursor/android-onnx-demo-doc-3875/android_case` |

手机上的 1.13 用户已经听过长文，原话是「听起来还行」。1.14 改成安装包不带角色：导入 `feibi.zip` 或 `sanyueqi.zip` 后，列表里多一个可选项。覆盖安装会把原来的 `android_case` 挪到 `characters/feibi`。分句和合成行为不要改。

## 已经做完、不要重做

按提交从早到晚，只保留还生效的结论：

1. 桌面端导出菲比的 ONNX 和参考特征，重放与桌面合成逐样本一致。
2. 大文件按 45 MiB（47185920 字节）切到 `android_case/models/chunks/` 和 `android_case/roberta/chunks/`。GitHub 单文件上限 100 MB。完整大 ONNX 不进 git。说明在各自的 `parts.json`。
3. 安卓应用下载或导入这些分片，拼到 `filesDir`。长度已经对上的跳过。
4. 声码器走 XNNPACK，打不开退回 CPU。文本编码、两段解码、韵律模型用 CPU。线程数 `min(6, 核数)`。解码循环复用 48 块 KV，停止条件是 bool。最多 500 步，按句计算。
5. 中文前端在手机上：分词、多音字、变调、儿化。目标句在送进模型前加句首「。」，对应 `Core/Inference.py`。这个句号变成停顿音素，不是读出「句号」两个字。
6. 韵律模型是 Hugging Face `litagin/chinese-roberta-wwm-ext-large-onnx` 的 FP16 权重，手机文件 `roberta/model_fp16.onnx`，599034176 字节，输出 float32 `[seq, 1024]`。输入 `input_ids`、`token_type_ids`、`attention_mask`。CPU、BASIC_OPT、不用 XNNPACK、不写 optimized 模型文件。会话常驻。
7. 分词表在 APK `assets/roberta/vocab.txt`，21128 行，一行一个 token，行号就是 id。不要用 HF 那个带空行的 `vocab.txt`，行号对不上。CLS 101，SEP 102，UNK 100。
8. `text_bert` 按归一化后的句子编码。原始行数必须等于 `len(word2ph)+2`（CLS + 字 + SEP）。去掉两端，再按 `word2ph` 重复。对不上就用全零，不要崩。
9. `ref_bert` 用参考句原文现算，只算一次并缓存。参考句不加句首句号。菲比原文是「在此之前，请您务必继续享受旅居拉古那的时光。」音素必须和包里的 `ref_seq` 42 个 id 一致，否则退回全零并打日志「参考音素和测试包对不上」。
10. 多音字在 RoBERTa 之前决定。单音字走 CEDICT。多音字走 G2pM。`polyphonic.txt` 里的整词优先（银行 → hang2）。变调只改声调，不改读音。RoBERTa 不改读音。
11. 长文使用与 `src/genie_tts/Utils/TextSplitter.py` 相同的规则，实现在 `android/onnx-demo/app/src/main/java/com/genie/onnxdemo/TextSplitter.kt`。单测对过桌面端输出。先切用户原文，再对每一段调用 `infer()`（每段自己加句首句号）。参考韵律和声学模型在循环外加载一次。一句没有音素就跳过这一句，不要弄崩后面的句子。

分句规则：

- 先删掉换行，换行不是断句。
- 有效长度：跳过标点，ASCII 记 1，其他字符记 2。
- 句号、问号、叹号、省略号，以及 `!?.`，在有效长度达到 5 时收成一句。更短的并进后面。单独一个「好。」后面没有字时，仍然留下。
- 逗号、顿号、分号、冒号、引号平时不断。有效长度达到 40（大约二十个汉字）再遇到这些才切开。
- `——` 在原实现里是一个两字标点。端口必须和桌面端一样，不要自作主张改成按破折号断句。单测里有这个例子。

只有一句时保存 `phone.wav`。多句保存 `phone-1.wav`、`phone-2.wav`。日志写「分成 N 句」和「第 i/N 句」。全部播完写「这 N 句都已播放」。

## 手机上已经认可的结果

这些不要再让用户重测，除非改动碰到了同一条路径。

- 七言「朝辞白帝彩云间，千里江陵一日还。」31 个音素，「一日还」是 `h ai2`。仍是 1 句。
- 洛神赋「远而望之，皎若太阳升朝霞。迫而察之，灼若芙蕖出渌波。」原先整段 49 个音素，「朝霞」是 `zh ao1 x ia2`。1.13 因为中间有句号，分成 2 句先后播放。第一句仍含 `zh ao1 x ia2`。这是规则，不是退步。用户早期要求「两句洛神赋算一句」指的是测试素材怎么数，后来明确要求按原仓库分句。
- 「战争的伟力之最深厚的根源，存在于民众之中。」41 个音素。仍是 1 句。
- 「今天天气不错，我们出去走走吧。」29 个音素。逗号不断。1.12 第二次：韵律约 191 ms，参考韵律 42（已缓存），encoder 3 ms，decoder 73 步 / 789 ms，声码器 1308 ms，总计 2271 ms，音频 2.88 s，实时率 0.79。
- 语气句用户听过「太好了，我们明天就能出发了！」和「你怎么现在才来，不是说好了三点吗？」，说感觉还行。
- 1.13 长文用户贴了两段叙事，都是「分成 3 句」，逗号因为超过约二十个字被切开，逐句播放。用户确认是逐句生产，并说听起来还行。第二段没有重新加载声学模型和参考韵律。

## 合成数据流

目标句：

1. `TextSplitter.split(用户原文)`。
2. 每一段 `ChineseFrontend.infer`，内部是 `phones("。" + 这段)`。得到 `text_seq` 和 `word2ph`。
3. RoBERTa 得到 `text_bert`，形状 `[音素数, 1024]`。
4. 编码器吃 `ref_seq`、`text_seq`、`ref_bert`、`text_bert`、`ssl_content`。
5. `t2s_encoder_fp32.onnx` → `t2s_first_stage_decoder_fp32.onnx` → 自回归 `t2s_stage_decoder_fp32.onnx` → `vits_fp32.onnx`。
6. 声码器输入是 `text_seq`、`pred_semantic`、`ge`、`ge_advanced`。声码器看不到 BERT。

`text_bert` 改变这一句的停顿和重音。`ref_bert` 改变新句子相对参考句的起伏。音色主要来自 `ssl_content`、`ge`、`ge_advanced`。所以换角色必须换声学模型和这三块参考特征，以及参考原文。只换 BERT 不会换成另一个人。

菲比包里的 `ref_bert.bin` 仍是全零。应用在内存里覆盖。`manifest.json` 的 `"roberta": false` 表示文件本身不是现算的。手机上旧的 manifest 可能没有 `ref_text`，代码用 `ReferenceLine.FEIBI` 兜底。不要为了这个字段强迫用户重新下载。

## 下一步

再加一个中文角色，并在应用里切换。

这个仓库的预置角色只有三个：菲比（中文）、mika（日语）、thirtyseven（英语）。不要做日语，不要做英语。第二个中文角色不在这个仓库里。

用户说模型在私有仓库：

`git@github.com:lostprobe99/genie-web-tts.git`

从 `Genie-TTS` 上启动的 Cloud Agent 读不到它。原因已经查过：当前任务的 GitHub 令牌被收成只有 `lostprobe99/Genie-TTS`，即使 Cursor 的 GitHub 应用显示 All repositories，这张令牌的可见仓库数也是 1。不要在这个仓库的 agent 里反复重试那个私有地址。

新 agent 必须从 `genie-web-tts` 开工，工作区就是那个私有仓库。开工第一件事是读这份交接（见文末链接），然后在本仓库里找第二个中文角色，不要先改安卓代码。

在 `genie-web-tts` 里要找清：

- 角色名。不要假设就是菲比。如果仓库里同时有菲比，菲比已经在手机上，找另一个中文角色。
- 是不是 V2ProPlus。`convert_to_onnx` 只支持 V2 和 V2ProPlus。不是这两种就停下来告诉用户，不要硬转。
- 权重是训练文件（`.ckpt` 加 `.pth`）还是已经转好的 ONNX。
- 参考录音文件，以及和录音逐字对应的原文。原文差一个字，参考音素就对不上，`ref_bert` 会退回全零。
- 不要把整个网站搬进 `Genie-TTS`。韵律模型也不要再带一份，手机里的中文 RoBERTa 共用。

找到之后，安卓侧的改动仍然落在公开仓库 `lostprobe99/Genie-TTS` 的分支 `cursor/android-onnx-demo-doc-3875`。不要改 `master`。不要为了同一件事再开一个新 PR，除非现有 PR 已经合上。用户讨厌多封邮件。

换角色时每个角色需要的文件，和现在的 `android_case/` 一样：

- 四份灌好权重的 FP32 ONNX：`t2s_encoder_fp32.onnx`、`t2s_first_stage_decoder_fp32.onnx`、`t2s_stage_decoder_fp32.onnx`、`vits_fp32.onnx`。桌面端转换入口是 `genie.convert_to_onnx(torch_pth_path, torch_ckpt_path, output_dir)`，见 `src/genie_tts/Converter/v2ProPlus/Converter.py`。手机包用的是自包含文件，不依赖旁边的 `.bin`。沿用菲比导出时的做法。
- `ref_seq`：参考原文的音素 id，不加句首句号。用 `chinese_to_phones` 或手机同一套 `ChineseFrontend.phones`。
- `ssl_content`、`ge`、`ge_advanced`：从这段参考录音提出来的特征。形状随录音长度变，`ge` 仍是 `[1, 1024, 1]`，`ge_advanced` 仍是 `[1, 512, 1]`。
- `ref_text` 写进该角色自己的 manifest。`ref_bert` 文件可以继续全零，由应用按原文现算。
- 大 ONNX 继续按 47185920 字节分片，写 `parts.json`。GitHub 警告线 50 MB，硬限制 100 MB。

应用上的切换：

- 菲比的目录留着。新角色用另一个目录，不要把 `android_case` 里菲比的四个声学模型覆盖掉。
- 界面上能选择角色。选中后，编码器、声码器、`ref_seq`、`ssl_content`、`ge`、`ge_advanced`、参考原文一起换。
- 换角色时清掉缓存的 `ref_bert` 和已经加载的 `TtsEngine`。RoBERTa 会话可以留着。
- 新角色的声学模型也走应用内下载，长度对上的跳过。不要做 13 个文件的手动导入。
- 参考音素和该角色包里的 `ref_seq` 不一致时，沿用现在的退路：日志说明对不上，`ref_bert` 用全零，不要崩。
- 升一档版本号。现在是 1.13 / versionCode 14。下一版用 1.14 / 15，除非中间还有别的已发布版本。
- 更新 `docs/android-onnx-demo.md`。APK 仍覆盖 `android/onnx-demo/genie-onnx-demo.apk`，raw 地址保持这条分支，用户才不用换书签。
- 提交信息沿用仓库风格：`feat: …` 或 `fix: …`。
- 不要提交 `android/onnx-demo/sentences/*.wav`，不要提交 `GenieData/`，不要提交完整大 ONNX。

推送若只是这份源码和几十 MB 的 APK，普通 `git push` 即可。以前推过约 572 MB 的韵律分片，那次才需要加大 `http.postBuffer` 并使用 HTTP/1.1。不要无故重推那些分片。

## 环境

这台云端机器上：

- 代码在 `/workspace`。Python 是 `/workspace/.venv/bin/python`，跑仓库代码时加 `PYTHONPATH=/workspace`。
- Gradle：`/opt/gradle/gradle-8.11.1/bin/gradle`。SDK：`/opt/android-sdk`。离线 `assembleDebug` 可以打出现在的包。
- 单测：在 `android/onnx-demo` 里跑 `:app:testDebugUnitTest`。现有的 `TextSplitterTest`、`ChineseFrontendTest`、`ReferenceLineTest`、`BertTokenizerTest`、`BertAlignTest`、`RobertaPartsTest` 必须保持通过。
- 改完源码再打包。打包后用 aapt 核对 versionName 和 versionCode，再把 `app/build/outputs/apk/debug/app-debug.apk` 复制到 `android/onnx-demo/genie-onnx-demo.apk`。
- `gh` 在这台机器上是只读的。开 PR、改 PR 用仓库里的 PR 工具，不要用 `gh pr create`。

## 明确不做

- 日语角色、日语小说、日语假名。
- 英语角色 thirtyseven。
- 把 stage decoder 放到 NPU / NNAPI。
- 声学模型改 FP16 或 INT8。
- 继续挤解码器或声码器的速度。性能一节在 1.6 停过，用户同意到此为止。
- 去掉目标句的句首句号。
- 改桌面端 `TextSplitter.py` 的规则来迁就手机。手机跟着桌面端。
- 让用户手动下载再导入十几个分片。

## 新 Agent 的第一段工作

1. 确认自己的工作区是 `lostprobe99/genie-web-tts`。如果 `gh repo view` 或列目录失败，停下来告诉用户授权没生效，不要猜模型在哪。
2. 找出第二个中文角色的名字、权重路径、参考录音、参考原文，以及是不是 V2ProPlus。把这四项写给用户确认，再开始转换和改安卓。
3. 确认之后，再把导出的角色包和切换界面做到 `Genie-TTS` 的 `cursor/android-onnx-demo-doc-3875`。一个可用的 APK 再推送一次。

用户可以在新 agent 的第一条消息里贴：

```text
先读 https://raw.githubusercontent.com/lostprobe99/Genie-TTS/cursor/android-onnx-demo-doc-3875/docs/android-agent-handoff.md
然后在这个仓库里找第二个中文角色。找到名字、权重、参考录音和参考原文后先告诉我，先不要改安卓。
```
