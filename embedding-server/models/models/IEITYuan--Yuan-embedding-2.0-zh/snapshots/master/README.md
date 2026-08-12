---
tags:
- mteb
language:
- zh
license: apache-2.0
library_name: sentence-transformers
---
<h2 align="left">Yuan-embedding-2.0-zh</h2>

Yuan-embedding-2.0-zh 是专门为中文文本检索任务设计的嵌入模型。我们在[Yuan-embedding-1.0](https://huggingface.co/IEITYuan/Yuan-embedding-1.0)的基础上，针对Retrieval任务与Reranking任务进行了进一步优化。主要工作如下：

- 数据增强
  - Hard negative sampling：利用Rerank模型与LLM进行双重评估，筛选出高质量正负样本
  - LLM合成数据：利用[Yuan2-M32](https://huggingface.co/IEITYuan/Yuan2-M32)针对训练数据query进行LLM重写
- 损失函数设计
  - Multi-Task loss
  - Matryoshka Representation Learning
  - Retrieval任务使用InfoNCE with in-batch-negative
  - 针对Reranking任务设计Margin-Adaptive Pairwise Ranking Loss
  

<h2 align="left">Usage</h2>

```bash
pip install -U sentence-transformers==3.4.1
```

使用示例：

```python
from sentence_transformers import SentenceTransformer

model = SentenceTransformer("IEITYuan/Yuan-embedding-2.0-zh")
sentences_1 = ["样例数据-1", "样例数据-2"]
sentences_2 = ["样例数据-3", "样例数据-4"]
embeddings_1 = model.encode(sentences_1, normalize_embeddings=True)
embeddings_2 = model.encode(sentences_2, normalize_embeddings=True)
similarity = embeddings_1 @ embeddings_2.T
print(similarity)
```
