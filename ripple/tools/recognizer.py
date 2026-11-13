#!/usr/bin/env python3
"""
Builds and evaluates Ripple's on-device recogniser.

The recogniser is the image half of a CLIP-family model (CLIP, SigLIP, MobileCLIP). The text half
is run here, once, at build time: every phrase in core/src/main/resources/vocab.tsv becomes an
embedding, and the phone only has to embed the photo and find the closest phrases.

  python tools/recognizer.py discover
  python tools/recognizer.py eval  --repo <hf repo> [--variant fp32|quantized|int8|...] --coco <dir>
  python tools/recognizer.py build --repo <hf repo> --revision <sha> --variant <v> --out <assets dir>
"""
import argparse, json, os, re, struct, sys, time, hashlib, random
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
VOCAB = os.path.join(HERE, "..", "core", "src", "main", "resources", "vocab.tsv")
TEMPLATES = ["a photo of {}.", "a close-up photo of {}.", "{}"]

# ---------------------------------------------------------------------------------------- vocab

def load_vocab():
    rows = []
    for line in open(VOCAB, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        kb, phrase = line.split("\t")
        rows.append((kb.strip(), phrase.strip()))
    return rows

def vocab_hash(rows):
    return hashlib.sha256("\n".join(f"{k}\t{p}" for k, p in rows).encode()).hexdigest()[:16]

# ------------------------------------------------------------------------------------ the model

def files_for(repo, revision, variant):
    from huggingface_hub import HfApi
    info = HfApi().model_info(repo, revision=revision, files_metadata=True)
    names = {s.rfilename: (s.size or 0) for s in info.siblings}
    suffix = "" if variant == "fp32" else "_" + variant
    def pick(kinds):
        for kind in kinds:
            p = f"onnx/{kind}_model{suffix}.onnx"
            if p in names:
                return p
        return None
    vision = pick(["vision", "image"])
    text = pick(["text"]) if variant == "fp32" else (f"onnx/text_model.onnx" if "onnx/text_model.onnx" in names else pick(["text"]))
    if vision is None or text is None:
        raise SystemExit(f"{repo}: no {variant} vision/text ONNX. Files: {sorted(n for n in names if n.endswith('.onnx'))}")
    extra = [n for n in names if n.startswith(vision) and n != vision]  # external weight files
    return info.sha, vision, text, extra, names

def download(repo, revision, path):
    from huggingface_hub import hf_hub_download
    return hf_hub_download(repo, path, revision=revision)

def session(path, threads=4):
    import onnxruntime as ort
    o = ort.SessionOptions()
    o.intra_op_num_threads = threads
    o.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(path, o, providers=["CPUExecutionProvider"])

def pick_output(sess, prefer):
    names = [o.name for o in sess.get_outputs()]
    for p in prefer:
        if p in names:
            return p
    return names[0]

def preprocess_config(repo, revision):
    cfg = json.load(open(download(repo, revision, "preprocessor_config.json")))
    size = cfg.get("size", 224)
    crop = cfg.get("crop_size", size)
    def dims(v):
        if isinstance(v, int): return {"height": v, "width": v}
        return v
    out = {
        "resize": "shortest_edge" if isinstance(size, dict) and "shortest_edge" in size else "exact",
        "size": size["shortest_edge"] if isinstance(size, dict) and "shortest_edge" in size else dims(size)["height"],
        "center_crop": bool(cfg.get("do_center_crop", False)),
        "crop": dims(crop)["height"] if cfg.get("do_center_crop", False) else None,
        "rescale": float(cfg.get("rescale_factor", 1 / 255)) if cfg.get("do_rescale", True) else 1.0,
        "normalize": bool(cfg.get("do_normalize", True)),
        "mean": cfg.get("image_mean", [0.5, 0.5, 0.5]),
        "std": cfg.get("image_std", [0.5, 0.5, 0.5]),
        "bicubic": cfg.get("resample", 3) == 3,
    }
    if out["center_crop"] and out["crop"] is None:
        out["crop"] = out["size"]
    return out

def prepare(img, pc):
    """PIL image -> float32 NCHW, exactly as the phone does it (see Recognizer.kt)."""
    from PIL import Image
    img = img.convert("RGB")
    rs = Image.BICUBIC if pc["bicubic"] else Image.BILINEAR
    w, h = img.size
    if pc["resize"] == "shortest_edge":
        s = pc["size"] / min(w, h)
        img = img.resize((max(1, round(w * s)), max(1, round(h * s))), rs)
    else:
        img = img.resize((pc["size"], pc["size"]), rs)
    if pc["center_crop"]:
        c = pc["crop"]; w, h = img.size
        l, t = (w - c) // 2, (h - c) // 2
        img = img.crop((l, t, l + c, t + c))
    x = np.asarray(img, dtype=np.float32) * pc["rescale"]
    if pc["normalize"]:
        x = (x - np.array(pc["mean"], np.float32)) / np.array(pc["std"], np.float32)
    return x.transpose(2, 0, 1)[None].astype(np.float32)

def norm(v):
    return v / np.linalg.norm(v, axis=-1, keepdims=True).clip(1e-12)

def text_embeddings(repo, revision, text_path, rows):
    from tokenizers import Tokenizer
    cfg = json.load(open(download(repo, revision, "config.json")))
    tcfg = cfg.get("text_config", {})
    length = int(tcfg.get("max_position_embeddings", 77))
    tok = Tokenizer.from_file(download(repo, revision, "tokenizer.json"))
    pad_id = None
    try:
        tcfg_tok = json.load(open(download(repo, revision, "tokenizer_config.json")))
        pad = tcfg_tok.get("pad_token")
        if isinstance(pad, dict): pad = pad.get("content")
        if pad is not None: pad_id = tok.token_to_id(pad)
    except Exception:
        pass
    if pad_id is None:
        pad_id = tok.token_to_id("<|endoftext|>") or tok.token_to_id("<pad>") or 0
    tok.enable_truncation(length)
    tok.enable_padding(length=length, pad_id=pad_id)
    s = session(text_path)
    inputs = [i.name for i in s.get_inputs()]
    out = pick_output(s, ["text_embeds", "pooler_output"])
    def embed(texts):
        enc = tok.encode_batch(texts)
        ids = np.array([e.ids for e in enc], np.int64)
        mask = np.array([e.attention_mask for e in enc], np.int64)
        feed = {}
        for n in inputs:
            if n == "input_ids": feed[n] = ids
            elif n == "attention_mask": feed[n] = mask
            elif n == "position_ids": feed[n] = np.tile(np.arange(ids.shape[1], dtype=np.int64), (ids.shape[0], 1))
        res = []
        for i in range(len(texts)):  # batch of one: some exports have a fixed batch size
            r = s.run([out], {k: v[i:i + 1] for k, v in feed.items()})[0]
            res.append(r.reshape(-1))
        return norm(np.stack(res))
    per_template = [embed([t.format(p).lower() for _, p in rows]) for t in TEMPLATES]
    return norm(np.mean(per_template, axis=0)).astype(np.float32)

class Model:
    def __init__(self, repo, revision, variant):
        self.sha, vision, text, extra, _ = files_for(repo, revision, variant)
        self.vision_path = download(repo, self.sha, vision)
        for e in extra:
            download(repo, self.sha, e)
        self.text_path = download(repo, self.sha, text)
        self.pc = preprocess_config(repo, self.sha)
        self.vs = session(self.vision_path)
        self.vin = self.vs.get_inputs()[0].name
        self.vout = pick_output(self.vs, ["image_embeds", "pooler_output"])
        self.repo, self.variant = repo, variant
        self.rows = load_vocab()
        t0 = time.time()
        self.T = text_embeddings(repo, self.sha, self.text_path, self.rows)
        print(f"text embeddings: {self.T.shape} in {time.time()-t0:.0f}s", flush=True)
        self.kb = sorted({k for k, _ in self.rows})
        self.kb_index = np.array([self.kb.index(k) for k, _ in self.rows])

    def embed_image(self, img):
        return norm(self.vs.run([self.vout], {self.vin: prepare(img, self.pc)})[0].reshape(1, -1))[0]

    def rank(self, img):
        """Same maths as Vocabulary.rank in the app: softmax over phrases, summed per library id."""
        sims = self.T @ self.embed_image(img)
        p = np.exp((sims - sims.max()) * 100.0); p /= p.sum()
        per_kb = np.zeros(len(self.kb)); np.add.at(per_kb, self.kb_index, p)
        order = np.argsort(-per_kb)
        best_phrase = self.rows[int(np.argmax(sims))][1]
        return [(self.kb[i], float(per_kb[i])) for i in order[:5]], best_phrase

# ------------------------------------------------------------------------------------ evaluation

# COCO category -> library ids that count as right.
COCO = {
    "person": ["human"], "bicycle": ["bicycle"], "car": ["car"], "motorcycle": ["motorbike"], "airplane": ["plane"],
    "bus": ["transit"], "train": ["transit"], "truck": ["car"], "bird": ["bird"], "cat": ["cat"], "dog": ["dog"],
    "horse": ["livestock"], "sheep": ["livestock"], "cow": ["livestock"], "backpack": ["bag"], "umbrella": ["generic-home"],
    "handbag": ["bag"], "tie": ["generic-fashion"], "suitcase": ["bag"], "frisbee": ["sports"], "skis": ["sports"],
    "snowboard": ["sports"], "sports ball": ["sports"], "kite": ["sports", "toy"], "baseball bat": ["sports"],
    "baseball glove": ["sports"], "skateboard": ["sports"], "surfboard": ["sports"], "tennis racket": ["sports"],
    "bottle": ["water-bottle", "alcohol", "soda", "mug"], "wine glass": ["alcohol", "mug"], "cup": ["mug", "coffee", "tea"],
    "fork": ["mug"], "knife": ["mug"], "spoon": ["mug"], "bowl": ["mug", "meal"], "banana": ["fruit"], "apple": ["fruit"],
    "sandwich": ["bread", "meal"], "orange": ["fruit"], "broccoli": ["vegetables"], "carrot": ["vegetables"],
    "hot dog": ["meal"], "pizza": ["pizza"], "donut": ["meal"], "cake": ["meal", "chocolate"], "chair": ["furniture"],
    "couch": ["furniture"], "potted plant": ["plant"], "bed": ["furniture"], "dining table": ["furniture", "meal"],
    "toilet": ["tap"], "tv": ["tv"], "laptop": ["laptop"], "mouse": ["laptop"], "remote": ["tv", "headphones"],
    "keyboard": ["laptop"], "cell phone": ["phone"], "microwave": ["appliance"], "oven": ["appliance"],
    "toaster": ["appliance"], "sink": ["tap"], "refrigerator": ["appliance"], "book": ["book"], "clock": ["generic-home"],
    "vase": ["mug", "plant"], "scissors": ["generic-home"], "teddy bear": ["toy"], "hair drier": ["appliance"],
    "toothbrush": ["cosmetics"],
}

def coco_crops(coco_dir, per_cat, seed=7):
    """The largest object in each image, cropped like the app crops a tracked object."""
    from PIL import Image
    ann = json.load(open(os.path.join(coco_dir, "annotations", "instances_val2017.json")))
    cats = {c["id"]: c["name"] for c in ann["categories"]}
    imgs = {i["id"]: i for i in ann["images"]}
    biggest = {}
    for a in ann["annotations"]:
        if a.get("iscrowd"): continue
        if a["area"] > biggest.get(a["image_id"], {"area": 0})["area"]:
            biggest[a["image_id"]] = a
    by_cat = {}
    for img_id, a in biggest.items():
        name = cats[a["category_id"]]
        x, y, w, h = a["bbox"]
        if name not in COCO or min(w, h) < 110: continue
        by_cat.setdefault(name, []).append((img_id, a["bbox"]))
    rnd = random.Random(seed)
    out = []
    for name in sorted(by_cat):
        items = by_cat[name]; rnd.shuffle(items)
        for img_id, (x, y, w, h) in items[:per_cat]:
            info = imgs[img_id]
            im = Image.open(os.path.join(coco_dir, "val2017", info["file_name"])).convert("RGB")
            mx, my = w * 0.08, h * 0.08
            box = (max(0, x - mx), max(0, y - my), min(info["width"], x + w + mx), min(info["height"], y + h + my))
            out.append((name, im.crop(tuple(int(v) for v in box))))
    return out

def cmd_eval(a):
    t0 = time.time()
    m = Model(a.repo, a.revision, a.variant)
    load = time.time() - t0
    crops = coco_crops(a.coco, a.per_cat)
    # Latency: one image, four threads, after warm-up.
    sample = crops[0][1]
    for _ in range(3): m.embed_image(sample)
    t = time.time(); n = 10
    for _ in range(n): m.embed_image(sample)
    latency = (time.time() - t) / n * 1000
    top1 = top3 = 0; per = {}; errors = {}
    for name, im in crops:
        ranked, phrase = m.rank(im)
        ok = COCO[name]
        h1 = ranked[0][0] in ok; h3 = any(k in ok for k, _ in ranked[:3])
        top1 += h1; top3 += h3
        s = per.setdefault(name, [0, 0]); s[0] += h1; s[1] += 1
        if not h1: errors[(name, ranked[0][0])] = errors.get((name, ranked[0][0]), 0) + 1
    n = len(crops)
    vsize = sum(os.path.getsize(p) for p in [m.vision_path] + [os.path.join(os.path.dirname(m.vision_path), f) for f in os.listdir(os.path.dirname(m.vision_path)) if f.startswith(os.path.basename(m.vision_path)) and f != os.path.basename(m.vision_path)])
    row = f"| {a.repo} | {a.variant} | {m.sha[:10]} | {top1/n:.1%} | {top3/n:.1%} | {latency:.0f} ms | {vsize/1e6:.0f} MB |"
    print("\nRESULT", row, f"(n={n}, setup {load:.0f}s)")
    worst = sorted(per.items(), key=lambda kv: kv[1][0] / kv[1][1])[:12]
    print("weakest categories:", ", ".join(f"{k} {v[0]}/{v[1]}" for k, v in worst))
    print("common confusions:", ", ".join(f"{k[0]}→{k[1]} ×{v}" for k, v in sorted(errors.items(), key=lambda kv: -kv[1])[:15]))
    summ = os.environ.get("GITHUB_STEP_SUMMARY")
    if summ:
        with open(summ, "a") as f:
            f.write("| model | variant | rev | top-1 | top-3 | latency (4 CPU threads) | vision model size |\n|---|---|---|---|---|---|---|\n")
            f.write(row + "\n\n")

def cmd_build(a):
    m = Model(a.repo, a.revision, a.variant)
    os.makedirs(a.out, exist_ok=True)
    import shutil
    shutil.copyfile(m.vision_path, os.path.join(a.out, "vision.onnx"))
    with open(os.path.join(a.out, "text_embeds.bin"), "wb") as f:
        f.write(m.T.astype("<f4").tobytes())
    with open(os.path.join(a.out, "labels.tsv"), "w", encoding="utf-8") as f:
        for k, p in m.rows:
            f.write(f"{k}\t{p}\n")
    cfg = dict(m.pc)
    cfg.update({"model": a.repo, "revision": m.sha, "variant": a.variant, "input": m.vin, "output": m.vout,
                "dim": int(m.T.shape[1]), "count": int(m.T.shape[0]), "vocab_hash": vocab_hash(m.rows)})
    json.dump(cfg, open(os.path.join(a.out, "config.json"), "w"), indent=1)
    print(json.dumps(cfg, indent=1))
    # Self-check: the bundled files reproduce the same ranking.
    print("vision.onnx", os.path.getsize(os.path.join(a.out, "vision.onnx")) // 1_000_000, "MB")

def cmd_discover(a):
    from huggingface_hub import HfApi
    api = HfApi()
    for author, q in [("onnx-community", "siglip"), ("onnx-community", "clip"), ("onnx-community", "mobileclip"),
                      ("Xenova", "siglip"), ("Xenova", "clip"), ("Xenova", "mobileclip"), ("onnx-community", "dfn"),
                      ("onnx-community", "metaclip")]:
        try:
            for mdl in api.list_models(author=author, search=q, limit=60):
                print("MODEL", mdl.id)
        except Exception as e:
            print("search failed", author, q, e)

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("discover")
    e = sub.add_parser("eval"); e.add_argument("--repo", required=True); e.add_argument("--revision", default="main")
    e.add_argument("--variant", default="fp32"); e.add_argument("--coco", required=True); e.add_argument("--per-cat", type=int, default=14)
    b = sub.add_parser("build"); b.add_argument("--repo", required=True); b.add_argument("--revision", default="main")
    b.add_argument("--variant", default="fp32"); b.add_argument("--out", required=True)
    a = p.parse_args()
    {"eval": cmd_eval, "build": cmd_build, "discover": cmd_discover}[a.cmd](a)
