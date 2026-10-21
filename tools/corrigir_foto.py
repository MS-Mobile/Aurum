#!/usr/bin/env python3
"""Corrige fotos desbotadas: cor, alcance dinâmico, vibrance, textura e grão de pele.

Uso:
    python3 corrigir_foto.py entrada.jpg saida.jpg [opções]

Exemplos de ajuste:
    --calor 0.03        mais quente      (negativo = mais frio)
    --vibrance 0.15     menos saturado
    --brilho 0.05       mais claro
    --grao 3.5          mais grão na pele
    --textura 0         sem realce de textura
    --mate 0.8          menos brilho (oleosidade) na pele
    --iphone            visual iPhone: HDR local, tom dourado e grão fino
"""
import argparse

import cv2
import numpy as np


def parse_args():
    p = argparse.ArgumentParser(description="Correção de fotos desbotadas estilo iPhone")
    p.add_argument("entrada")
    p.add_argument("saida")
    p.add_argument("--wb", type=float, default=0.35, help="força do balanço de branco (0-1)")
    p.add_argument("--calor", type=float, default=0.015, help="aquecimento de cor (-0.05 a 0.05)")
    p.add_argument("--brilho", type=float, default=0.0, help="ajuste de exposição (-0.2 a 0.2)")
    p.add_argument("--clahe", type=float, default=0.3, help="peso do contraste local (0-1)")
    p.add_argument("--curva", type=float, default=0.13, help="força da curva em S (0-0.4)")
    p.add_argument("--vibrance", type=float, default=0.25, help="vibrance (0-0.5)")
    p.add_argument("--textura", type=float, default=1.0, help="multiplicador de realce de textura (0-2)")
    p.add_argument("--grao", type=float, default=2.6, help="desvio padrão do grão de pele (0-5)")
    p.add_argument("--grao-tamanho", type=float, default=0.5, help="tamanho do grão (0.3 fino a 1.0 grosso)")
    p.add_argument("--hdr", type=float, default=0.0, help="HDR local estilo Smart HDR (0-1)")
    p.add_argument("--dourado", type=float, default=0.0, help="tonalização dourada (0-1)")
    p.add_argument("--mate", type=float, default=0.0, help="tira o brilho (oleosidade) da pele (0-1)")
    p.add_argument("--iphone", action="store_true",
                   help="atalho: --wb 0.25 --calor 0.045 --clahe 0.15 --curva 0.2 --vibrance 0.28 "
                        "--textura 0.5 --hdr 0.45 --dourado 1 --mate 0.8 --grao 0.8 --grao-tamanho 0.3")
    p.add_argument("--qualidade", type=int, default=95, help="qualidade JPEG")
    p.add_argument("--sem-comparacao", action="store_true", help="não gerar imagem antes|depois")
    args = p.parse_args()
    if args.iphone:
        defaults = {"wb": 0.25, "calor": 0.045, "clahe": 0.15, "curva": 0.2, "vibrance": 0.28, "textura": 0.5,
                    "hdr": 0.45, "dourado": 1.0, "mate": 0.8, "grao": 0.8, "grao_tamanho": 0.3}
        for key, value in defaults.items():
            if getattr(args, key) == p.get_default(key):
                setattr(args, key, value)
    return args


def to_u8(img):
    return np.clip(img * 255, 0, 255).astype(np.uint8)


def white_balance(img, strength, warmth):
    means = img.reshape(-1, 3).mean(0)
    gain = 1 + strength * (means.mean() / means - 1)
    gain *= np.array([1 - warmth, 1.0, 1 + warmth])  # BGR
    return np.clip(img * gain, 0, 1)


def levels(img, exposure):
    y = img.mean(2)
    lo, hi = np.percentile(y, (0.5, 99.8))
    lo *= 0.6
    img = (img - lo) / max(hi - lo, 1e-6)
    return np.clip(img * (1 + exposure), 0, 1)


def dynamic_range(img, clahe_weight, curve):
    lab = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=1.2, tileGridSize=(8, 8))
    l2 = cv2.addWeighted(clahe.apply(l), clahe_weight, l, 1 - clahe_weight, 0)
    x = l2.astype(np.float32) / 255.0
    s_curve = 0.5 - 0.5 * np.cos(np.pi * x)
    x = (1 - curve) * x + curve * s_curve
    highlights = np.clip((x - 0.85) / 0.15, 0, 1)
    x -= 0.04 * highlights  # protege brilho de testa/nariz
    l2 = np.clip(x * 255, 0, 255).astype(np.uint8)
    return cv2.cvtColor(cv2.merge([l2, a, b]), cv2.COLOR_LAB2BGR).astype(np.float32) / 255.0


def local_hdr(img, amount):
    """Smart HDR: separa base (iluminação) e detalhe, comprime a base e abre sombras."""
    if amount <= 0:
        return img
    lab = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2LAB).astype(np.float32)
    l = lab[..., 0] / 255.0
    log_l = np.log(l + 0.01)
    h, w = l.shape
    small = cv2.resize(log_l, (w // 4, h // 4), interpolation=cv2.INTER_AREA)
    small = cv2.bilateralFilter(small, d=15, sigmaColor=0.4, sigmaSpace=max(small.shape) / 60)
    base = cv2.resize(small, (w, h), interpolation=cv2.INTER_LINEAR)
    detail = log_l - base
    mid = np.median(base)
    base = mid + (base - mid) * (1 - 0.45 * amount)  # comprime o alcance tonal
    out = np.exp(base + detail * (1 + 0.15 * amount)) - 0.01
    # abre sombras e segura altas luzes
    shadows = np.clip(1 - out / 0.45, 0, 1) ** 2
    out = out + 0.08 * amount * shadows
    out = np.where(out > 0.9, 0.9 + (out - 0.9) * (1 - 0.5 * amount), out)
    lab[..., 0] = np.clip(out, 0, 1) * 255
    return cv2.cvtColor(lab.astype(np.uint8), cv2.COLOR_LAB2BGR).astype(np.float32) / 255.0


def golden_tone(img, amount):
    """Tonalização dourada nos médios e altas luzes; sombras ficam neutras."""
    if amount <= 0:
        return img
    lab = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2LAB).astype(np.float32)
    t = lab[..., 0] / 255.0
    weight = np.clip((t - 0.25) / 0.5, 0, 1) * np.clip((1.0 - t) / 0.12, 0, 1)
    lab[..., 2] += 9 * amount * weight   # amarelo
    lab[..., 1] += 2.5 * amount * weight  # toque de vermelho = ouro, não verde-limão
    lab = np.clip(lab, 0, 255)
    return cv2.cvtColor(lab.astype(np.uint8), cv2.COLOR_LAB2BGR).astype(np.float32) / 255.0


def vibrance(img, amount):
    hsv = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2HSV).astype(np.float32)
    hue, sat = hsv[..., 0], hsv[..., 1] / 255.0
    skin = ((hue <= 25) | (hue >= 170)) & (sat > 0.08)
    boost = amount * (1 - sat)
    boost = np.where(skin, boost * 0.5, boost)
    hsv[..., 1] = np.clip(sat * (1 + boost), 0, 1) * 255
    return cv2.cvtColor(hsv.astype(np.uint8), cv2.COLOR_HSV2BGR).astype(np.float32) / 255.0


def skin_mask(img):
    ycc = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2YCrCb)
    mask = cv2.inRange(ycc, (40, 135, 85), (255, 175, 130)).astype(np.float32) / 255
    return cv2.GaussianBlur(mask, (0, 0), 6)


def matte(img, amount):
    """Tira o brilho da pele: escurece as altas luzes da pele e puxa a cor delas para a da pele ao redor."""
    if amount <= 0:
        return img
    mask = skin_mask(img)
    lab = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2LAB).astype(np.float32)
    t = lab[..., 0] / 255.0
    shine = np.clip((t - 0.7) / 0.25, 0, 1)
    h, w = t.shape
    small = (w // 4, h // 4)
    # pele por perto, incluindo os pontos brancos de brilho dentro dela
    near = cv2.GaussianBlur(cv2.resize(mask, small, interpolation=cv2.INTER_AREA), (0, 0), 3)
    near = cv2.resize(np.clip(near * 1.5, 0, 1), (w, h), interpolation=cv2.INTER_LINEAR)
    amount_px = shine * near * amount
    lab[..., 0] -= amount_px * (t - 0.7) * 255 * 0.6
    # cor da pele ao redor: média ponderada pela pele sem brilho
    weight = mask * (1 - shine) + 1e-3
    weight_blur = cv2.GaussianBlur(cv2.resize(weight, small, interpolation=cv2.INTER_AREA), (0, 0), 10)
    for c in (1, 2):
        local = cv2.GaussianBlur(cv2.resize(lab[..., c] * weight, small, interpolation=cv2.INTER_AREA), (0, 0), 10)
        local = cv2.resize(local / weight_blur, (w, h), interpolation=cv2.INTER_LINEAR)
        lab[..., c] += amount_px * 0.7 * (local - lab[..., c])
    lab = np.clip(lab, 0, 255)
    return cv2.cvtColor(lab.astype(np.uint8), cv2.COLOR_LAB2BGR).astype(np.float32) / 255.0


def texture_and_grain(img, texture, grain, grain_size=0.5):
    mask = skin_mask(img)
    lab = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2LAB).astype(np.float32)
    l = lab[..., 0]
    detail = l - cv2.GaussianBlur(l, (0, 0), 1.2)
    l = l + detail * texture * (0.25 + 0.35 * mask)

    rng = np.random.default_rng()
    noise = cv2.GaussianBlur(rng.normal(0, 1, l.shape).astype(np.float32), (0, 0), grain_size)
    noise /= noise.std()
    midtones = np.clip(1 - np.abs(l / 255.0 - 0.55) / 0.45, 0, 1)
    l = l + noise * grain * midtones * (0.35 + 0.65 * mask)

    lab[..., 0] = np.clip(l, 0, 255)
    return cv2.cvtColor(lab.astype(np.uint8), cv2.COLOR_LAB2BGR)


def main():
    args = parse_args()
    original = cv2.imread(args.entrada)
    if original is None:
        raise SystemExit(f"Não foi possível abrir {args.entrada}")

    img = original.astype(np.float32) / 255.0
    img = white_balance(img, args.wb, args.calor)
    img = levels(img, args.brilho)
    img = dynamic_range(img, args.clahe, args.curva)
    img = local_hdr(img, args.hdr)
    img = vibrance(img, args.vibrance)
    img = golden_tone(img, args.dourado)
    img = matte(img, args.mate)
    result = texture_and_grain(img, args.textura, args.grao, args.grao_tamanho)  # sempre por último

    cv2.imwrite(args.saida, result, [cv2.IMWRITE_JPEG_QUALITY, args.qualidade])
    print(f"Salvo: {args.saida}")

    if not args.sem_comparacao:
        both = np.hstack([original, result])
        both = cv2.resize(both, (both.shape[1] // 2, both.shape[0] // 2), interpolation=cv2.INTER_AREA)
        comp = args.saida.rsplit(".", 1)[0] + "_antes_depois.jpg"
        cv2.imwrite(comp, both, [cv2.IMWRITE_JPEG_QUALITY, 90])
        print(f"Comparação: {comp}")


if __name__ == "__main__":
    main()
