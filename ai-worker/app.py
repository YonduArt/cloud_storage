import hashlib
import os
import tempfile
from typing import Any

import numpy as np
from fastapi import FastAPI, File, UploadFile
from PIL import Image
from pydantic import BaseModel

app = FastAPI(title="Cloud Storage AI Worker")

TEXT_MODEL_NAME = os.getenv("TEXT_MODEL", "intfloat/multilingual-e5-small")
IMAGE_MODEL_NAME = os.getenv("IMAGE_MODEL", "ViT-B-32")
IMAGE_PRETRAINED = os.getenv("IMAGE_PRETRAINED", "laion400m_e32")
OCR_ENABLED = os.getenv("OCR_ENABLED", "true").lower() == "true"
MODEL_MODE = os.getenv("MODEL_MODE", "auto").lower()

_text_model: Any | None = None
_clip_model: Any | None = None
_clip_preprocess: Any | None = None
_clip_tokenizer: Any | None = None
_ocr: Any | None = None


class TextPayload(BaseModel):
    text: str


class QueryPayload(BaseModel):
    query: str


def _fallback_vector(text: str, size: int) -> list[float]:
    vector = np.zeros(size, dtype=np.float32)
    tokens = [token for token in text.lower().replace("_", " ").split() if token]
    if not tokens:
        tokens = [text or "empty"]
    for token in tokens:
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        for i, byte in enumerate(digest):
            vector[(byte + i * 17) % size] += 1.0
    norm = np.linalg.norm(vector)
    if norm > 0:
        vector = vector / norm
    return vector.astype(float).tolist()


def _load_text_model():
    global _text_model
    if _text_model is not None:
        return _text_model
    if MODEL_MODE == "fallback":
        return None
    try:
        from sentence_transformers import SentenceTransformer

        _text_model = SentenceTransformer(TEXT_MODEL_NAME)
    except Exception:
        _text_model = None
    return _text_model


def _load_clip():
    global _clip_model, _clip_preprocess, _clip_tokenizer
    if _clip_model is not None:
        return _clip_model, _clip_preprocess, _clip_tokenizer
    if MODEL_MODE == "fallback":
        return None, None, None
    try:
        import open_clip

        _clip_model, _, _clip_preprocess = open_clip.create_model_and_transforms(
            IMAGE_MODEL_NAME,
            pretrained=IMAGE_PRETRAINED,
        )
        _clip_model.eval()
        _clip_tokenizer = open_clip.get_tokenizer(IMAGE_MODEL_NAME)
    except Exception:
        _clip_model, _clip_preprocess, _clip_tokenizer = None, None, None
    return _clip_model, _clip_preprocess, _clip_tokenizer


def _load_ocr():
    global _ocr
    if _ocr is not None:
        return _ocr
    if not OCR_ENABLED or MODEL_MODE == "fallback":
        return None
    try:
        from paddleocr import PaddleOCR

        _ocr = PaddleOCR(use_angle_cls=True, lang="ru", show_log=False)
    except Exception:
        _ocr = None
    return _ocr


def text_embedding(text: str, input_type: str = "passage") -> list[float]:
    model = _load_text_model()
    if model is None:
        return _fallback_vector(text, 384)
    prefix = "query: " if input_type == "query" else "passage: "
    embedding = model.encode(prefix + text[:120_000], normalize_embeddings=True)
    return np.asarray(embedding, dtype=float).tolist()


def image_embedding(path: str) -> list[float]:
    model, preprocess, _ = _load_clip()
    if model is None or preprocess is None:
        with open(path, "rb") as image_file:
            return _fallback_vector(hashlib.sha256(image_file.read()).hexdigest(), 512)
    import torch

    image = preprocess(Image.open(path).convert("RGB")).unsqueeze(0)
    with torch.no_grad():
        embedding = model.encode_image(image)
        embedding = embedding / embedding.norm(dim=-1, keepdim=True)
    return embedding.squeeze(0).cpu().numpy().astype(float).tolist()


def clip_text_embedding(text: str) -> list[float]:
    model, _, tokenizer = _load_clip()
    if model is None or tokenizer is None:
        return _fallback_vector(text, 512)
    import torch

    tokens = tokenizer([text])
    with torch.no_grad():
        embedding = model.encode_text(tokens)
        embedding = embedding / embedding.norm(dim=-1, keepdim=True)
    return embedding.squeeze(0).cpu().numpy().astype(float).tolist()


def ocr_text(path: str) -> str:
    ocr = _load_ocr()
    if ocr is None:
        return ""
    try:
        result = ocr.ocr(path, cls=True)
        lines: list[str] = []
        for page in result or []:
            for item in page or []:
                if len(item) > 1 and len(item[1]) > 0:
                    lines.append(str(item[1][0]))
        return "\n".join(lines)
    except Exception:
        return ""


@app.get("/health")
def health():
    return {
        "status": "UP",
        "textModel": TEXT_MODEL_NAME,
        "imageModel": IMAGE_MODEL_NAME,
        "mode": MODEL_MODE,
    }


@app.post("/analyze/text")
def analyze_text(payload: TextPayload):
    limited = payload.text[:120_000]
    return {
            "description": "Text document indexed locally",
            "extractedText": limited,
            "textEmbedding": text_embedding(limited, "passage"),
            "textModel": TEXT_MODEL_NAME if _load_text_model() is not None else "fallback-hash-384",
        }


@app.post("/analyze/image")
async def analyze_image(file: UploadFile = File(...)):
    suffix = os.path.splitext(file.filename or "image.bin")[1] or ".bin"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(await file.read())
        path = tmp.name
    try:
        extracted = ocr_text(path)
        return {
            "description": "Image indexed locally",
            "ocrText": extracted,
            "imageEmbedding": image_embedding(path),
            "textEmbedding": text_embedding(extracted, "passage") if extracted.strip() else None,
            "imageModel": IMAGE_MODEL_NAME if _load_clip()[0] is not None else "fallback-hash-512",
            "textModel": (TEXT_MODEL_NAME if _load_text_model() is not None else "fallback-hash-384") if extracted.strip() else None,
            "ocrModel": "PaddleOCR" if extracted.strip() else None,
        }
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


@app.post("/embed/query/text")
def embed_query_text(payload: QueryPayload):
    return {
        "embedding": text_embedding(payload.query, "query"),
        "model": TEXT_MODEL_NAME if _load_text_model() is not None else "fallback-hash-384",
    }


@app.post("/embed/query/image")
def embed_query_image(payload: QueryPayload):
    return {
        "embedding": clip_text_embedding(payload.query),
        "model": IMAGE_MODEL_NAME if _load_clip()[0] is not None else "fallback-hash-512",
    }
