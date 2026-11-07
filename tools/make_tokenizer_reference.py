"""Records what thumbsup2's tokenizers do, for the app's Kotlin port to be
tested against:

  * encode: sentence-transformers' all-MiniLM-L6-v2 tokenizer (the one its
    embeddings go through), on texts chosen to hit every branch: accents,
    CJK, punctuation, control characters, emoji, long words, truncation;
  * decode: BLIP's tokenizer turning generated ids back into a caption,
    exactly as thumbsup2 calls it (skip_special_tokens=True, then strip()).

Run in thumbsup2's environment:
    ~/gallery-tools/.venv/bin/python tools/make_tokenizer_reference.py
Writes tools/reference/tokenizer.json.
"""

import json
import os
import random
from pathlib import Path

os.environ.setdefault("HF_HUB_OFFLINE", "1")

from sentence_transformers import SentenceTransformer
from transformers import BlipProcessor

HERE = Path(__file__).resolve().parent

TEXTS = [
    "two cats sleeping on a couch",
    "A Red Double-Decker Bus, driving down the street!",
    "INVOICE 2047 Bill to: Northwind Traders Date: March 14, 2026",
    "Café crème brûlée à la française — naïve résumé",
    "Ångström Øresund Łódź straße ﬁne ligature",
    "東京タワーの夜景 and 北京 mixed with English",
    "한국어 텍스트 테스트",
    "emoji 😀👍🏽 family 👨‍👩‍👧 flags 🇺🇸",
    "tab\there\nnewline\r\ncarriage\x00null\x07bell​zero-width",
    "don't won't it's they're I've we'll",
    "e-mail@example.com https://example.com/path?q=1&r=2#frag",
    "$100.00 — 50% off!!! (limited) [time] {only} <now> ~~~ ^_^ `code` |pipe| \\back",
    "supercalifragilisticexpialidocious antidisestablishmentarianism",
    "x" * 120,
    "pneumonoultramicroscopicsilicovolcanoconiosis" * 3,
    "  leading and trailing spaces   ",
    "",
    "   ",
    "UPPER lower MiXeD",
    "numbers 3.14159 1,000,000 2nd 21st 1990s",
    "Ελληνικά Русский текст עברית العربية हिन्दी",
    "á composed vs á precomposed",
    "‘curly’ “quotes” … ellipsis  nbsp",
    "word " * 300,
    "WHEN THE COUCH ISALL YOURS",
    "Grandma Are you coming to dinner on Sunday? Yes! What time should we be there?",
    "©®™ °C ½ ¼ ² ³ µ",
    "unaffable unbelievable uncharacteristically",
    "[CLS] [SEP] [MASK] [UNK] [PAD] literal special tokens",
    "﻿bom ­soft-hyphen  line-sep",
]


def main():
    st = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2", device="cpu")
    tok = st.tokenizer
    encode = []
    for t in TEXTS:
        enc = tok([t], truncation=True, max_length=st.max_seq_length)
        encode.append({"text": t, "ids": enc["input_ids"][0]})

    blip = BlipProcessor.from_pretrained("Salesforce/blip-image-captioning-base")
    btok = blip.tokenizer
    rng = random.Random(1234)
    sequences = []
    # Real captions, encoded then decoded the way BLIP's output is.
    for caption in [
        "two cats sleeping on a couch", "a text message with the text ' are you going down sunday?? '",
        "the settings menu for the samsung s9", "exit sign - exit sign - exit", "a man's hat isn't red",
        "a sign that says, \" stop! \" on it", "a cat ( or dog ) on a bed.", "what's up ? i'm fine , we're ok",
        "a woman's hand holding a 10 $ bill", "it ' s a dog", "he said ' hi ' to me",
    ]:
        ids = [30522] + btok(caption, add_special_tokens=False)["input_ids"] + [102]
        sequences.append(ids)
    # Random id runs, with specials and the two ids past BERT's vocabulary
    # (30522 is BLIP's [DEC] start token).
    for _ in range(300):
        n = rng.randint(1, 30)
        ids = [30522]
        for _ in range(n):
            r = rng.random()
            if r < 0.05:
                ids.append(rng.choice([0, 100, 101, 102, 103, 30522, 30523]))
            elif r < 0.25:
                ids.append(rng.randint(999, 1100))  # punctuation and short tokens live here
            else:
                ids.append(rng.randint(1, 30521))
        sequences.append(ids)
    decode = [{"ids": ids, "text": blip.decode(ids, skip_special_tokens=True).strip()}
              for ids in sequences]

    out = HERE / "reference" / "tokenizer.json"
    out.write_text(json.dumps({"maxSeqLength": st.max_seq_length, "encode": encode,
                               "decode": decode}, ensure_ascii=False, indent=0),
                   encoding="utf-8")
    print(f"{len(encode)} encode cases, {len(decode)} decode cases -> {out}")
    for d in decode[:11]:
        print(repr(d["text"]))


if __name__ == "__main__":
    main()
