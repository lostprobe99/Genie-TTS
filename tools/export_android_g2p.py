"""Export the Chinese frontend resources used by the Android demo.

The phone port must reproduce chinese_to_phones(), including the leading
period added in Core/Inference.py. This script writes those resources and a
golden file of phone ids.
"""

import json
import pickle
import shutil
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "android/onnx-demo/app/src/main/assets/g2p"
GOLDEN = ROOT / "android/onnx-demo/app/src/test/resources/golden_phones.txt"


def write_lines(path: Path, lines) -> None:
    path.write_text("".join(f"{line}\n" for line in lines), encoding="utf-8")


def export_jieba() -> None:
    import jieba_fast

    source = Path(jieba_fast.__file__).parent / "dict.txt"
    shutil.copyfile(source, ASSET / "jieba_dict.txt")


def export_hmm() -> None:
    from jieba_fast.finalseg.prob_emit import P as final_emit
    from jieba_fast.finalseg.prob_start import P as final_start
    from jieba_fast.finalseg.prob_trans import P as final_trans
    from jieba_fast.posseg.char_state_tab import P as char_state
    from jieba_fast.posseg.prob_emit import P as pos_emit
    from jieba_fast.posseg.prob_start import P as pos_start
    from jieba_fast.posseg.prob_trans import P as pos_trans

    def state_name(state) -> str:
        if isinstance(state, tuple):
            return f"{state[0]}/{state[1]}"
        return str(state)

    lines = ["START"]
    for state, prob in final_start.items():
        lines.append(f"{state_name(state)}\t{prob:.17g}")
    lines.append("TRANS")
    for src, table in final_trans.items():
        for dst, prob in table.items():
            lines.append(f"{state_name(src)}\t{state_name(dst)}\t{prob:.17g}")
    lines.append("EMIT")
    for state, table in final_emit.items():
        for char, prob in table.items():
            lines.append(f"{state_name(state)}\t{char}\t{prob:.17g}")
    write_lines(ASSET / "hmm_final.txt", lines)

    lines = ["START"]
    for state, prob in pos_start.items():
        lines.append(f"{state_name(state)}\t{prob:.17g}")
    lines.append("TRANS")
    for src, table in pos_trans.items():
        for dst, prob in table.items():
            lines.append(f"{state_name(src)}\t{state_name(dst)}\t{prob:.17g}")
    lines.append("EMIT")
    for state, table in pos_emit.items():
        for char, prob in table.items():
            lines.append(f"{state_name(state)}\t{char}\t{prob:.17g}")
    lines.append("CHARS")
    for char, states in char_state.items():
        names = ",".join(state_name(state) for state in states)
        lines.append(f"{char}\t{names}")
    write_lines(ASSET / "hmm_pos.txt", lines)


def export_g2pm() -> None:
    from g2pM.g2pM import G2pM

    model = G2pM()
    names = [
        "embeddings",
        "weight_ih",
        "weight_hh",
        "bias_ih",
        "bias_hh",
        "weight_ih_reverse",
        "weight_hh_reverse",
        "bias_ih_reverse",
        "bias_hh_reverse",
        "hidden_weight_l0",
        "hidden_bias_l0",
        "hidden_weight_l1",
        "hidden_bias_l1",
    ]
    blobs = []
    meta = []
    for name in names:
        array = np.ascontiguousarray(getattr(model, name), dtype=np.float32)
        blobs.append(array.tobytes())
        meta.append(f"{name}\t{','.join(str(dim) for dim in array.shape)}")
    (ASSET / "g2pm_f32.bin").write_bytes(b"".join(blobs))
    write_lines(ASSET / "g2pm_shapes.txt", meta)
    write_lines(
        ASSET / "g2pm_chars.txt",
        (f"{char}\t{idx}" for char, idx in model.char2idx.items()),
    )
    write_lines(
        ASSET / "g2pm_classes.txt",
        (f"{idx}\t{pron}" for idx, pron in model.idx2class.items()),
    )
    cedict = []
    for char, prons in model.cedict.items():
        cedict.append(char + "\t" + " ".join(prons))
    write_lines(ASSET / "g2pm_cedict.txt", cedict)


def export_tables() -> None:
    from genie_tts.G2P.Chinese.Erhua import ErhuaProcessor
    from genie_tts.G2P.Chinese.Normalization.char_convert import t2s_dict
    from genie_tts.G2P.Chinese.ToneSandhi import ToneSandhi
    from genie_tts.G2P.SymbolsV2 import symbols_v2
    from pypinyin.constants import PHRASES_DICT, PINYIN_DICT
    from pypinyin.phonetic_symbol import phonetic_symbol

    chinese = ROOT / "GenieData/G2P/ChineseG2P"
    shutil.copyfile(chinese / "opencpop-strict.txt", ASSET / "opencpop.txt")
    polyphonic = pickle.load(open(chinese / "polyphonic.pickle", "rb"))
    write_lines(
        ASSET / "polyphonic.txt",
        (word + "\t" + " ".join(pinyins) for word, pinyins in polyphonic.items()),
    )
    sandhi = ToneSandhi()
    write_lines(ASSET / "must_neural.txt", sorted(sandhi.must_neural_tone_words))
    write_lines(ASSET / "must_not_neural.txt", sorted(sandhi.must_not_neural_tone_words))
    erhua = ErhuaProcessor()
    write_lines(ASSET / "must_erhua.txt", sorted(erhua.must_erhua))
    write_lines(ASSET / "not_erhua.txt", sorted(erhua.not_erhua))
    write_lines(ASSET / "symbols.txt", symbols_v2)
    write_lines(
        ASSET / "t2s.txt",
        (f"{trad}\t{simp}" for trad, simp in t2s_dict.items() if trad != simp),
    )
    write_lines(
        ASSET / "pinyin_char.txt",
        (f"{chr(int(code))}\t{pinyin.split(',')[0]}" for code, pinyin in PINYIN_DICT.items()),
    )
    phrases = []
    for word, readings in PHRASES_DICT.items():
        first = " ".join(item[0] for item in readings)
        phrases.append(f"{word}\t{first}")
    write_lines(ASSET / "phrases.txt", phrases)
    write_lines(
        ASSET / "phonetic.txt",
        (f"{symbol}\t{replacement}" for symbol, replacement in phonetic_symbol.items()),
    )


def export_golden() -> None:
    from genie_tts.G2P.Chinese.ChineseG2P import chinese_to_phones

    texts = [
        "朝辞白帝彩云间，千里江陵一日还。",
        "远而望之，皎若太阳升朝霞。迫而察之，灼若芙蕖出渌波。",
        "战争的伟力之最深厚的根源，存在于民众之中。",
        "今天天气不错，我们出去走走吧。",
        "2024年3月5日下午3:30，气温-3°C。",
        "小院儿里有100个漂亮姑娘。",
        "看一看这段不怕。",
        "银行里的衣服放在木头上。",
    ]
    blocks = []
    for text in texts:
        normalized, phones, ids, _ = chinese_to_phones("。" + text)
        blocks.append(
            "\n".join(
                [
                    f"TEXT {text}",
                    f"NORM {normalized}",
                    "PHONES " + " ".join(phones),
                    "IDS " + " ".join(str(item) for item in ids),
                ]
            )
        )
    GOLDEN.parent.mkdir(parents=True, exist_ok=True)
    GOLDEN.write_text("\n\n".join(blocks) + "\n", encoding="utf-8")


def main() -> None:
    ASSET.mkdir(parents=True, exist_ok=True)
    export_jieba()
    export_hmm()
    export_g2pm()
    export_tables()
    export_golden()
    sizes = sorted((path.stat().st_size, path.name) for path in ASSET.iterdir())
    for size, name in sizes:
        print(f"{size:10d} {name}")


if __name__ == "__main__":
    main()
