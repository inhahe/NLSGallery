"""Exports the ONNX graphs the app runs thumbsup2's models with.

The graphs carry no weights of their own. Every large tensor is an ONNX
"external data" reference (file name, byte offset, length) into the official
weight file thumbsup2 itself loads:

    BLIP caption model   model.safetensors   (HuggingFace Salesforce/blip-image-captioning-base)
    MiniLM embedder      model.safetensors   (HuggingFace sentence-transformers/all-MiniLM-L6-v2)
    EasyOCR detector     craft_mlt_25k.pth   (JaidedAI/EasyOCR GitHub release)
    EasyOCR recognizer   english_g2.pth      (JaidedAI/EasyOCR GitHub release)

so the app ships only the small graphs and downloads the weights from their
publishers, byte for byte the files thumbsup2 runs. A tensor the exporter has
to rearrange (an LSTM's gates, which ONNX orders differently from PyTorch)
cannot point into the original file and is stored in the graph instead; the
script reports each one, and refuses any over INLINE_LIMIT bytes.

Run in thumbsup2's Python environment plus onnx/onnxruntime (tools/README.md):
    ~/gallery-tools/.venv/bin/python tools/export_models.py

Writes app/src/main/assets/models/<model>/*.onnx and
app/src/main/assets/models/models.json (what the app downloads, with sizes and
SHA-256), and checks every graph against PyTorch with ONNX Runtime.
"""

import hashlib
import json
import os
import sys
from pathlib import Path

os.environ.setdefault("HF_HUB_OFFLINE", "1")

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnx import TensorProto, numpy_helper
from torch import nn

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "app" / "src" / "main" / "assets" / "models"
STAGING = Path.home() / "gallery-tools" / "staging"   # graphs + weight symlinks, for checking
HF = Path.home() / ".cache" / "huggingface" / "hub"
EASYOCR = Path.home() / ".EasyOCR" / "model"

INLINE_LIMIT = 4 * 1024 * 1024  # a rearranged tensor bigger than this is an error
OPSET = 17

# Where each weight file comes from. The app downloads `url`, takes `member`
# out of it when it is a zip, and checks the result against `sha256`.
SOURCES = {
    "blip": {
        "file": "model.safetensors",
        "url": "https://huggingface.co/Salesforce/blip-image-captioning-base/resolve/"
               "4c26dfece70e02028433dd192458a54b390b85d2/model.safetensors",
        "local": HF / "models--Salesforce--blip-image-captioning-base" / "blobs" /
                 "90f4c493dfc6f4c2152c8bfcfd52ee141055feb835eea2dceb8b9231e5dbf17c",
    },
    "minilm": {
        "file": "model.safetensors",
        "url": "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/"
               "c9745ed1d9f207416be6d2e6f8de32d1f16199bf/model.safetensors",
        "local": HF / "models--sentence-transformers--all-MiniLM-L6-v2" / "blobs" /
                 "53aa51172d142c89d9012cce15ae4d6cc0ca6895895114379cacb4fab128d9db",
    },
    "craft": {
        "file": "craft_mlt_25k.pth",
        "url": "https://github.com/JaidedAI/EasyOCR/releases/download/pre-v1.1.6/craft_mlt_25k.zip",
        "member": "craft_mlt_25k.pth",
        "local": EASYOCR / "craft_mlt_25k.pth",
    },
    "english_g2": {
        "file": "english_g2.pth",
        "url": "https://github.com/JaidedAI/EasyOCR/releases/download/v1.3/english_g2.zip",
        "member": "english_g2.pth",
        "local": EASYOCR / "english_g2.pth",
    },
}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- weight index

class WeightIndex:
    """Maps the bytes of every tensor in a weight file to where they sit in it."""

    def __init__(self, path, tensors):
        self.path = Path(path)
        self.blob = self.path.read_bytes()
        self.by_digest = {}
        for t in tensors:
            raw = t.detach().cpu().contiguous().numpy().tobytes()
            if not raw:
                continue
            off = self.blob.find(raw)
            if off < 0:
                raise SystemExit(f"{self.path.name}: a {tuple(t.shape)} tensor is not stored verbatim")
            self.by_digest.setdefault(hashlib.sha1(raw).digest(), (off, len(raw)))

    def locate(self, raw):
        return self.by_digest.get(hashlib.sha1(raw).digest())


def externalize(model, index, file_name, report):
    """Points every initializer found in the weight file at it; returns the
    total size of those left inline."""
    inline = 0
    for init in model.graph.initializer:
        raw = numpy_helper.to_array(init).tobytes()
        where = index.locate(raw) if len(raw) >= 64 else None
        if where is None:
            inline += len(raw)
            if len(raw) > 4096:
                report.append(f"  inline {init.name} {list(init.dims)} ({len(raw)} bytes)")
            if len(raw) > INLINE_LIMIT:
                raise SystemExit(f"{init.name}: {len(raw)} bytes is not in {file_name} and too big to inline")
            continue
        off, length = where
        init.ClearField("raw_data")
        init.ClearField("float_data")
        init.ClearField("int64_data")
        init.ClearField("int32_data")
        init.data_location = TensorProto.EXTERNAL
        del init.external_data[:]
        for k, v in (("location", file_name), ("offset", str(off)), ("length", str(length))):
            e = init.external_data.add()
            e.key, e.value = k, v
    return inline


def export(module, args, name, model_key, input_names, output_names, dynamic_axes, index, report):
    """Exports `module`, rewrites its weights as references, saves it, and
    returns the path of the staged copy (beside a link to the weight file)."""
    out_dir = ASSETS / model_key
    out_dir.mkdir(parents=True, exist_ok=True)
    tmp = STAGING / model_key / (name + ".full.onnx")
    tmp.parent.mkdir(parents=True, exist_ok=True)
    module.eval()
    with torch.no_grad():
        torch.onnx.export(
            module, args, str(tmp), input_names=input_names, output_names=output_names,
            dynamic_axes=dynamic_axes, opset_version=OPSET, do_constant_folding=False,
            dynamo=False,
        )
    model = onnx.load(str(tmp), load_external_data=True)
    file_name = SOURCES[model_key]["file"]
    inline = externalize(model, index, file_name, report)
    dest = out_dir / (name + ".onnx")
    onnx.save(model, str(dest))
    staged = STAGING / model_key / (name + ".onnx")
    staged.write_bytes(dest.read_bytes())
    # A hard link, not a symlink: ONNX Runtime refuses external data whose
    # resolved path leaves the model's directory.
    link = STAGING / model_key / file_name
    if link.is_symlink():
        link.unlink()
    if not link.exists():
        os.link(SOURCES[model_key]["local"], link)
    tmp.unlink()
    report.append(f"{model_key}/{name}.onnx: {dest.stat().st_size} bytes, {inline} inline")
    return staged


def session(path):
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def check_close(name, got, want, atol):
    err = float(np.max(np.abs(got - want)))
    if err > atol:
        raise SystemExit(f"{name}: ONNX differs from PyTorch by {err} (> {atol})")
    return err


# ------------------------------------------------------------------------ BLIP

class BlipVision(nn.Module):
    """pixel_values -> the cross-attention keys and values of all 12 decoder
    layers. They depend only on the image, so they are computed once per
    caption rather than at every generated token."""

    def __init__(self, model):
        super().__init__()
        self.vision = model.vision_model
        self.layers = model.text_decoder.bert.encoder.layer

    def forward(self, pixel_values):
        emb = self.vision(pixel_values=pixel_values)[0]  # [1, 577, 768]
        out = []
        for layer in self.layers:
            att = layer.crossattention.self
            shape = (emb.shape[0], emb.shape[1], att.num_attention_heads, att.attention_head_size)
            out.append(att.key(emb).view(shape).transpose(1, 2))
            out.append(att.value(emb).view(shape).transpose(1, 2))
        return tuple(out)


class BlipDecoderStep(nn.Module):
    """One greedy-decoding step of BLIP's text decoder: the newest token, the
    self-attention cache so far and the image's cross-attention keys/values
    -> logits for the next token and the cache extended by this one.

    Mirrors transformers' BlipTextLMHeadModel forward (5.5.4) operation for
    operation, with its cache split into explicit tensors."""

    def __init__(self, model):
        super().__init__()
        dec = model.text_decoder
        self.emb = dec.bert.embeddings
        self.layers = dec.bert.encoder.layer
        self.head = dec.cls

    @staticmethod
    def attend(att, q_in, k, v):
        shape = (q_in.shape[0], q_in.shape[1], att.num_attention_heads, att.attention_head_size)
        q = att.query(q_in).view(shape).transpose(1, 2)
        scores = torch.matmul(q, k.transpose(-1, -2)) / (att.attention_head_size ** 0.5)
        probs = torch.softmax(scores, dim=-1)
        ctx = torch.matmul(probs, v).permute(0, 2, 1, 3).contiguous()
        return ctx.view(ctx.shape[0], ctx.shape[1], att.all_head_size)

    def forward(self, input_ids, *kv):
        n = len(self.layers)
        past, cross = kv[:2 * n], kv[2 * n:]
        past_len = past[0].shape[2]
        pos = self.emb.position_ids[:, past_len:past_len + input_ids.shape[1]]
        h = self.emb.word_embeddings(input_ids) + self.emb.position_embeddings(pos)
        h = self.emb.LayerNorm(h)
        presents = []
        for i, layer in enumerate(self.layers):
            sa = layer.attention.self
            shape = (h.shape[0], h.shape[1], sa.num_attention_heads, sa.attention_head_size)
            k = torch.cat([past[2 * i], sa.key(h).view(shape).transpose(1, 2)], dim=2)
            v = torch.cat([past[2 * i + 1], sa.value(h).view(shape).transpose(1, 2)], dim=2)
            presents += [k, v]
            h1 = layer.attention.output(self.attend(sa, h, k, v), h)
            ca = layer.crossattention.self
            h2 = layer.crossattention.output(self.attend(ca, h1, cross[2 * i], cross[2 * i + 1]), h1)
            h = layer.output(layer.intermediate(h2), h2)
        logits = self.head(h)[:, -1, :]
        return (logits, *presents)


def export_blip(report):
    from transformers import BlipForConditionalGeneration, BlipProcessor
    from safetensors.torch import load_file
    from PIL import Image

    model_id = "Salesforce/blip-image-captioning-base"
    model = BlipForConditionalGeneration.from_pretrained(model_id).eval()
    processor = BlipProcessor.from_pretrained(model_id)
    src = SOURCES["blip"]["local"]
    index = WeightIndex(src, load_file(str(src)).values())
    n = len(model.text_decoder.bert.encoder.layer)

    vision_path = export(
        BlipVision(model), (torch.zeros(1, 3, 384, 384),), "blip_vision", "blip",
        ["pixel_values"], [f"cross_{kv}_{i}" for i in range(n) for kv in "kv"], None, index, report)

    names_past = [f"past_{kv}_{i}" for i in range(n) for kv in "kv"]
    names_cross = [f"cross_{kv}_{i}" for i in range(n) for kv in "kv"]
    names_present = [f"present_{kv}_{i}" for i in range(n) for kv in "kv"]
    dyn = {name: {2: "past"} for name in names_past}
    dyn.update({name: {2: "total"} for name in names_present})
    past = [torch.zeros(1, 12, 3, 64) for _ in names_past]
    cross = [torch.zeros(1, 12, 577, 64) for _ in names_cross]
    decoder_path = export(
        BlipDecoderStep(model), (torch.tensor([[30522]]), *past, *cross), "blip_decoder", "blip",
        ["input_ids", *names_past, *names_cross], ["logits", *names_present], dyn, index, report)

    # Check: captions from ONNX Runtime, decoding greedily the way the app
    # will, against transformers' own generate() on the test images.
    vis, dec = session(vision_path), session(decoder_path)
    sep, bos = model.config.text_config.sep_token_id, model.config.text_config.bos_token_id
    data = HERE / "testdata"
    checked = 0
    for name in sorted(json.loads((data / "manifest.json").read_text(encoding="utf-8"))):
        with Image.open(data / name) as im:
            pv = processor(im.convert("RGB"), return_tensors="pt")["pixel_values"]
        with torch.no_grad():
            want = model.generate(pixel_values=pv, max_new_tokens=40)[0].tolist()
        cross_vals = vis.run(None, {"pixel_values": pv.numpy()})
        feeds = {k: v for k, v in zip(names_cross, cross_vals)}
        for k in names_past:
            feeds[k] = np.zeros((1, 12, 0, 64), dtype=np.float32)
        ids = [bos]
        for _ in range(40):
            feeds["input_ids"] = np.array([[ids[-1]]], dtype=np.int64)
            out = dec.run(None, feeds)
            nxt = int(np.argmax(out[0][0]))
            ids.append(nxt)
            for k, v in zip(names_past, out[1:]):
                feeds[k] = v
            if nxt == sep:
                break
        if ids != want:
            raise SystemExit(f"BLIP {name}: ONNX {ids} != transformers {want}")
        checked += 1
    report.append(f"blip: ONNX greedy decoding equals transformers generate() on {checked} images")


# ---------------------------------------------------------------------- MiniLM

class MiniLM(nn.Module):
    """sentence-transformers' all-MiniLM-L6-v2 pipeline after tokenizing:
    BERT, mean pooling over the attention mask, L2 normalization.

    BERT's forward is written out over the module's own weights: transformers
    5's mask helpers cannot be traced. Same operations as its eager path."""

    def __init__(self, bert):
        super().__init__()
        self.emb = bert.embeddings
        self.layers = bert.encoder.layer

    def forward(self, input_ids, attention_mask):
        n = input_ids.shape[1]
        pos = self.emb.position_ids[:, :n]
        h = (self.emb.word_embeddings(input_ids) + self.emb.position_embeddings(pos)
             + self.emb.token_type_embeddings(torch.zeros_like(input_ids)))
        h = self.emb.LayerNorm(h)
        # (1 - mask) * most negative float: masked keys get no attention.
        bias = (1.0 - attention_mask[:, None, None, :].to(h.dtype)) * torch.finfo(h.dtype).min
        for layer in self.layers:
            sa = layer.attention.self
            heads = sa.num_attention_heads
            size = sa.attention_head_size
            shape = (h.shape[0], h.shape[1], heads, size)
            q = sa.query(h).view(shape).transpose(1, 2)
            k = sa.key(h).view(shape).transpose(1, 2)
            v = sa.value(h).view(shape).transpose(1, 2)
            scores = torch.matmul(q, k.transpose(-1, -2)) / (size ** 0.5) + bias
            ctx = torch.matmul(torch.softmax(scores, dim=-1), v).permute(0, 2, 1, 3).contiguous()
            ctx = ctx.view(h.shape[0], h.shape[1], heads * size)
            a = layer.attention.output.LayerNorm(layer.attention.output.dense(ctx) + h)
            h = layer.output.LayerNorm(layer.output.dense(layer.intermediate(a)) + a)
        tok = h
        mask = attention_mask.unsqueeze(-1).to(tok.dtype)
        pooled = (tok * mask).sum(1) / torch.clamp(mask.sum(1), min=1e-9)
        return torch.nn.functional.normalize(pooled, p=2, dim=1)


def export_minilm(report):
    from sentence_transformers import SentenceTransformer
    from safetensors.torch import load_file

    st = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2", device="cpu")
    bert = st[0].auto_model.eval()
    src = SOURCES["minilm"]["local"]
    index = WeightIndex(src, load_file(str(src)).values())
    ids = torch.tensor([[101, 1037, 3899, 2006, 1037, 6411, 102]])
    path = export(
        MiniLM(bert), (ids, torch.ones_like(ids)), "minilm", "minilm",
        ["input_ids", "attention_mask"], ["embedding"],
        {"input_ids": {0: "batch", 1: "tokens"}, "attention_mask": {0: "batch", 1: "tokens"}},
        index, report)
    sess = session(path)
    texts = ["two cats sleeping on a couch", "a red double decker bus driving down a street",
             "INVOICE 2047 Bill to: Northwind Traders", "shoreline at the ocean", "x"]
    worst = 0.0
    for t in texts + ["word " * 400]:  # the last is truncated to max_seq_length
        want = st.encode([t], normalize_embeddings=True)[0]
        enc = st.tokenizer([t], return_tensors="np", truncation=True, max_length=st.max_seq_length)
        got = sess.run(None, {"input_ids": enc["input_ids"].astype(np.int64),
                              "attention_mask": enc["attention_mask"].astype(np.int64)})[0][0]
        worst = max(worst, check_close(f"minilm {t!r}", got, want, 1e-5))
    report.append(f"minilm: max |ONNX - sentence-transformers| = {worst:.2e}; "
                  f"max_seq_length {st.max_seq_length}")


# --------------------------------------------------------------------- EasyOCR

def load_pth(path):
    sd = torch.load(str(path), map_location="cpu", weights_only=False)
    return {k[7:] if k.startswith("module.") else k: v for k, v in sd.items()}


class CraftOut(nn.Module):
    """CRAFT with only the score maps: [1, H/2, W/2, 2] (text, link)."""

    def __init__(self, net):
        super().__init__()
        self.net = net

    def forward(self, x):
        return self.net(x)[0]


class RecognizerOut(nn.Module):
    """english_g2 recognizer without its unused `text` argument:
    [1, 1, 64, W] in -> [1, T, 97] class scores (before softmax).

    Its AdaptiveAvgPool2d((None, 1)) averages the 3 rows the VGG leaves; the
    exporter cannot take that with a variable width, so it is a mean here."""

    def __init__(self, net):
        super().__init__()
        self.net = net

    def forward(self, x):
        feature = self.net.FeatureExtraction(x)                # [1, 256, 3, T]
        feature = feature.permute(0, 3, 1, 2).mean(dim=3)      # [1, T, 256]
        return self.net.Prediction(self.net.SequenceModeling(feature).contiguous())


def export_easyocr(report):
    from easyocr.craft import CRAFT
    from easyocr.model.vgg_model import Model

    craft = CRAFT()
    sd = load_pth(SOURCES["craft"]["local"])
    craft.load_state_dict(sd)
    index = WeightIndex(SOURCES["craft"]["local"], sd.values())
    path = export(CraftOut(craft), (torch.zeros(1, 3, 320, 480),), "craft", "craft",
                  ["image"], ["scores"], {"image": {2: "h", 3: "w"}, "scores": {1: "h2", 2: "w2"}},
                  index, report)
    sess = session(path)
    rng = np.random.default_rng(0)
    x = rng.standard_normal((1, 3, 256, 384)).astype(np.float32)
    with torch.no_grad():
        want = craft(torch.from_numpy(x))[0].numpy()
    err = check_close("craft", sess.run(None, {"image": x})[0], want, 1e-3)
    report.append(f"craft: max |ONNX - PyTorch| = {err:.2e}")

    export_craft_half(report)

    rec = Model(input_channel=1, output_channel=256, hidden_size=256, num_class=97)
    sd = load_pth(SOURCES["english_g2"]["local"])
    rec.load_state_dict(sd)
    index = WeightIndex(SOURCES["english_g2"]["local"], sd.values())
    path = export(RecognizerOut(rec), (torch.zeros(1, 1, 64, 256),), "english_g2", "english_g2",
                  ["image"], ["scores"], {"image": {3: "w"}, "scores": {1: "t"}}, index, report)
    sess = session(path)
    worst = 0.0
    for w in (64, 192, 640):
        x = rng.uniform(-1, 1, (1, 1, 64, w)).astype(np.float32)
        with torch.no_grad():
            want = rec(torch.from_numpy(x), None).numpy()
        worst = max(worst, check_close(f"english_g2 w={w}", sess.run(None, {"image": x})[0], want, 1e-3))
    report.append(f"english_g2: max |ONNX - PyTorch| = {worst:.2e}")


def export_craft_half(report):
    """craft_fp16.onnx: CRAFT computing in float16, for a phone's GPU (an Adreno runs it five
    times as fast as float32, in half the memory). Its weights stay where craft.onnx points, as
    float32 in the official file, and are cast inside the graph (ONNX Runtime folds the casts when
    it opens the session); the image in and the scores out stay float32."""
    from onnxconverter_common import float16

    src = ASSETS / "craft" / "craft.onnx"
    refs = {i.name: i for i in onnx.load(str(src), load_external_data=False).graph.initializer}
    # The weights read by hand: onnx refuses a file with several hard links (the staging one).
    loaded = onnx.load(str(src), load_external_data=False)
    blob = Path(SOURCES["craft"]["local"]).read_bytes()
    for init in loaded.graph.initializer:
        if init.data_location == TensorProto.EXTERNAL:
            info = {e.key: e.value for e in init.external_data}
            off, length = int(info["offset"]), int(info["length"])
            init.raw_data = blob[off:off + length]
            init.data_location = TensorProto.DEFAULT
            del init.external_data[:]
    half = float16.convert_float_to_float16(loaded, keep_io_types=True)

    # Every float32 weight the conversion turned into float16 goes back to its reference, cast.
    inits, casts, renamed = [], [], {}
    for init in half.graph.initializer:
        ref = refs.get(init.name)
        if (ref is not None and init.data_type == TensorProto.FLOAT16 and ref.data_type == TensorProto.FLOAT
                and ref.data_location == TensorProto.EXTERNAL):
            inits.append(ref)
            renamed[init.name] = init.name + "_fp16"
            casts.append(onnx.helper.make_node("Cast", [init.name], [renamed[init.name]],
                                               name=init.name + "_to_fp16", to=TensorProto.FLOAT16))
        else:
            inits.append(init)
    for node in half.graph.node:
        for i, name in enumerate(node.input):
            if name in renamed:
                node.input[i] = renamed[name]
    nodes = casts + list(half.graph.node)
    del half.graph.node[:]
    half.graph.node.extend(nodes)
    del half.graph.initializer[:]
    half.graph.initializer.extend(inits)
    dest = ASSETS / "craft" / "craft_fp16.onnx"
    onnx.save(half, str(dest))
    staged = STAGING / "craft" / "craft_fp16.onnx"
    staged.write_bytes(dest.read_bytes())
    inline = sum(len(numpy_helper.to_array(i).tobytes()) for i in inits if i.data_location != TensorProto.EXTERNAL)
    report.append(f"craft/craft_fp16.onnx: {dest.stat().st_size} bytes, {inline} inline, {len(casts)} weights cast")

    # Against float32 on an image-like input: float16 keeps about three significant digits.
    rng = np.random.default_rng(0)
    x = rng.standard_normal((1, 3, 256, 384)).astype(np.float32)
    want = session(STAGING / "craft" / "craft.onnx").run(None, {"image": x})[0]
    got = session(staged).run(None, {"image": x})[0]
    err = float(np.abs(got - want).max())
    scale = float(np.abs(want).max())
    if err > 0.02 * scale:
        raise SystemExit(f"craft_fp16: max |fp16 - fp32| = {err:.2e}, scores up to {scale:.2e}")
    report.append(f"craft_fp16: max |fp16 - fp32| = {err:.2e} (scores up to {scale:.2e})")


# ------------------------------------------------------------------------ main

def main():
    report = []
    for key, src in SOURCES.items():
        if not Path(src["local"]).exists():
            raise SystemExit(f"{key}: no local copy at {src['local']} (run thumbsup2 with AI on once)")
    if "--craft-half" in sys.argv:
        # Only the float16 detector, from the craft.onnx already exported.
        export_craft_half(report)
    else:
        export_minilm(report)
        export_easyocr(report)
        export_blip(report)

    # Both models tokenize with bert-base-uncased's vocabulary; the app ships it once.
    vocabs = {p.read_bytes() for p in HF.glob("models--*/snapshots/*/vocab.txt")
              if "blip-image-captioning-base" in str(p) or "all-MiniLM-L6-v2" in str(p)}
    if len(vocabs) != 1:
        raise SystemExit("BLIP's and MiniLM's vocab.txt differ: the app would need both")
    (ASSETS / "vocab.txt").write_bytes(vocabs.pop())

    manifest = {}
    for key, src in SOURCES.items():
        local = Path(src["local"])
        entry = {"file": src["file"], "url": src["url"], "size": local.stat().st_size,
                 "sha256": sha256_file(local),
                 "graphs": sorted(p.name for p in (ASSETS / key).glob("*.onnx"))}
        if "member" in src:
            entry["member"] = src["member"]
        manifest[key] = entry
    (ASSETS / "models.json").write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8")
    print("\n".join(report))


if __name__ == "__main__":
    sys.exit(main())
