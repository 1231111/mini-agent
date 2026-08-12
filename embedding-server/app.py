"""OpenAI 兼容 Embedding 服务：本地 Yuan-embedding-2.0-zh。"""
from __future__ import annotations

import os
import time
from typing import List, Union

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

MODEL_ID = os.getenv("MODEL_ID", "IEITYuan/Yuan-embedding-2.0-zh")
MODEL_DIR = os.getenv("MODEL_DIR", "").strip()
DEVICE = os.getenv("DEVICE", "cpu")
MODEL_NAME = os.getenv("MODEL_NAME", "Yuan-embedding-2.0-zh")

app = FastAPI(title="Yuan Embedding Server")
_model: SentenceTransformer | None = None
_dim = 0


class EmbedRequest(BaseModel):
    input: Union[str, List[str]]
    model: str | None = None
    encoding_format: str | None = None


class EmbedData(BaseModel):
    object: str = "embedding"
    embedding: List[float]
    index: int


class EmbedResponse(BaseModel):
    object: str = "list"
    data: List[EmbedData]
    model: str
    usage: dict = Field(default_factory=lambda: {
        "prompt_tokens": 0, "total_tokens": 0
    })


@app.on_event("startup")
def startup() -> None:
    global _model, _dim
    path = MODEL_DIR if MODEL_DIR else MODEL_ID
    t0 = time.time()
    print(f"[yuan-embed] loading path={path} device={DEVICE}", flush=True)
    local_only = bool(MODEL_DIR)
    _model = SentenceTransformer(
        path, device=DEVICE, local_files_only=local_only
    )
    _dim = int(_model.get_sentence_embedding_dimension())
    print(
        f"[yuan-embed] ready dim={_dim} "
        f"elapsed={time.time() - t0:.1f}s",
        flush=True,
    )


@app.get("/health")
def health():
    if _model is None:
        raise HTTPException(503, "model not loaded")
    return {"status": "ok", "model": MODEL_NAME, "dimension": _dim}


@app.get("/v1/models")
def models():
    return {
        "object": "list",
        "data": [{
            "id": MODEL_NAME,
            "object": "model",
            "owned_by": "local",
        }],
    }


@app.post("/v1/embeddings", response_model=EmbedResponse)
def embeddings(req: EmbedRequest):
    if _model is None:
        raise HTTPException(503, "model not loaded")
    texts = req.input if isinstance(req.input, list) else [req.input]
    if not texts:
        raise HTTPException(400, "input empty")
    vecs = _model.encode(
        texts,
        normalize_embeddings=True,
        convert_to_numpy=True,
        show_progress_bar=False,
    )
    if isinstance(vecs, list):
        vecs = np.asarray(vecs)
    data = [
        EmbedData(embedding=v.astype(float).tolist(), index=i)
        for i, v in enumerate(vecs)
    ]
    return EmbedResponse(data=data, model=req.model or MODEL_NAME)
