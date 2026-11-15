"""Convert Real-ESRGAN `realesr-general-x4v3` (SRVGGNetCompact, BSD-3-Clause) to LiteRT/TFLite.

The exported graph is only the conv + PReLU trunk (NHWC in, NHWC out with 48 channels).
The final pixel-shuffle and the nearest-neighbour residual are done by the app
(AiUpscaler.kt) so every op in the model is GPU-delegate friendly.

    pip install torch litert-torch numpy
    curl -LO https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth
    python convert_model.py realesr-general-x4v3.pth ../app/src/main/assets/sr_x4.tflite
"""
import sys

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

TILE = 192
SCALE = 4


class Trunk(nn.Module):
    def __init__(self, num_feat=64, num_conv=32):
        super().__init__()
        self.body = nn.ModuleList()
        self.body.append(nn.Conv2d(3, num_feat, 3, 1, 1))
        self.body.append(nn.PReLU(num_parameters=num_feat))
        for _ in range(num_conv):
            self.body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            self.body.append(nn.PReLU(num_parameters=num_feat))
        self.body.append(nn.Conv2d(num_feat, 3 * SCALE * SCALE, 3, 1, 1))

    def forward(self, x):
        for layer in self.body:
            x = layer(x)
        return x


class NhwcTrunk(nn.Module):
    def __init__(self, trunk):
        super().__init__()
        self.trunk = trunk

    def forward(self, x):  # NHWC -> NHWC
        return self.trunk(x.permute(0, 3, 1, 2)).permute(0, 2, 3, 1)


def full_model(trunk, x_nchw):
    """Reference: the original network's forward pass."""
    out = F.pixel_shuffle(trunk(x_nchw), SCALE)
    return out + F.interpolate(x_nchw, scale_factor=SCALE, mode="nearest")


def app_side_shuffle(t, x):
    """What AiUpscaler.kt does with the trunk output (NHWC numpy)."""
    _, h, w, _ = t.shape
    out = np.zeros((h * SCALE, w * SCALE, 3), np.float32)
    for c in range(3):
        for i in range(SCALE):
            for j in range(SCALE):
                out[i::SCALE, j::SCALE, c] = t[0, :, :, c * 16 + i * SCALE + j] + x[0, :, :, c]
    return out


def main(src, dst):
    sd = torch.load(src, map_location="cpu")
    sd = sd.get("params_ema", sd.get("params", sd))
    trunk = Trunk()
    trunk.load_state_dict(sd, strict=True)
    trunk.eval()

    import litert_torch

    sample = torch.rand(1, TILE, TILE, 3)
    edge = litert_torch.convert(NhwcTrunk(trunk).eval(), (sample,))
    edge.export(dst)

    # Verify the exported file against the original network.
    from ai_edge_litert.interpreter import Interpreter

    it = Interpreter(model_path=dst)
    it.allocate_tensors()
    i0, o0 = it.get_input_details()[0], it.get_output_details()[0]
    print("input", i0["shape"], "output", o0["shape"])
    it.set_tensor(i0["index"], sample.numpy())
    it.invoke()
    got = app_side_shuffle(it.get_tensor(o0["index"]), sample.numpy())
    with torch.no_grad():
        want = full_model(trunk, sample.permute(0, 3, 1, 2))[0].permute(1, 2, 0).numpy()
    err = float(np.abs(got - want).max())
    print("max abs error vs PyTorch reference:", err)
    assert err < 1e-3, err
    ops = sorted({d["op_name"] for d in it._get_ops_details()})
    print("ops:", ops)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
