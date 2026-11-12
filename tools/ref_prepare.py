"""生成参照 x0/ple（复刻 serve_llm.py 的 prepare），用于和端侧 sl-pre 逐值对比。"""
import json
import os
import sys

import numpy as np
import torch
import torch.nn.functional as F
from safetensors import safe_open
from transformers import AutoTokenizer, Gemma4Config

PREFIX = "model.language_model."
MODEL = "D:/gemma4-E4B"
ST = "D:/gemma4-E4B/model.safetensors"
OUT = "D:/pocket-model-studio/.ref"
T = 1024

prompt = sys.argv[1] if len(sys.argv) > 1 else "用三句话解释为什么 decode 是 memory bound。"

cfg = Gemma4Config.from_pretrained(MODEL).text_config
tok = AutoTokenizer.from_pretrained(MODEL)
f = safe_open(ST, "pt")

text = tok.apply_chat_template([{"role": "user", "content": prompt}],
                               tokenize=False, add_generation_prompt=True)
ids = tok.encode(text, add_special_tokens=False)
n = len(ids)
print("[ref] text =", repr(text))
print("[ref] ids(%d) = %s" % (n, ids))

padded = np.pad(np.array(ids, np.int32), (0, T - n))
tl = [int(i) for i in padded]

sl_ple = f.get_slice(f"{PREFIX}embed_tokens_per_layer.weight")
rows = torch.cat([sl_ple[i:i + 1] for i in tl]).float()
rows = rows.reshape(1, T, cfg.num_hidden_layers, cfg.hidden_size_per_layer_input)
rows *= cfg.hidden_size_per_layer_input ** 0.5

sl_emb = f.get_slice(f"{PREFIX}embed_tokens.weight")
emb = torch.cat([sl_emb[i:i + 1] for i in tl]).float() * cfg.hidden_size ** 0.5
emb = emb.unsqueeze(0)

wp = f.get_tensor(f"{PREFIX}per_layer_model_projection.weight").float()
wn = f.get_tensor(f"{PREFIX}per_layer_projection_norm.weight").float()

proj = F.linear(emb, wp) * (cfg.hidden_size ** -0.5)
proj = proj.reshape(1, T, cfg.num_hidden_layers, cfg.hidden_size_per_layer_input)
proj = proj * torch.rsqrt((proj * proj).mean(-1, keepdim=True) + cfg.rms_norm_eps)
proj = proj * wn
ple = ((proj + rows) * (2.0 ** -0.5)).float().numpy()

emb0 = emb.float().numpy()
os.makedirs(OUT, exist_ok=True)
emb0.tofile(os.path.join(OUT, "x0.bin"))
for L in range(cfg.num_hidden_layers):
    ple[:, :, L, :].reshape(T, -1).astype(np.float32).tofile(os.path.join(OUT, f"ple_{L}.bin"))

json.dump({"n_real": n, "ids": ids, "rms_eps": cfg.rms_norm_eps,
           "hidden": cfg.hidden_size, "hidden_pl": cfg.hidden_size_per_layer_input,
           "n_layers": cfg.num_hidden_layers},
          open(os.path.join(OUT, "meta.json"), "w"))

print("[ref] rms_eps =", cfg.rms_norm_eps, " hidden =", cfg.hidden_size,
      " per_layer =", cfg.hidden_size_per_layer_input, " layers =", cfg.num_hidden_layers)
print("[ref] x0 mean/std =", float(emb0.mean()), float(emb0.std()))
print("[ref] ple_0 mean/std =", float(ple[:, :, 0, :].mean()), float(ple[:, :, 0, :].std()))
