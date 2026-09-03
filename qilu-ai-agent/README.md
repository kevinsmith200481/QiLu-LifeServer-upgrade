# Qilu AI Agent

Python FastAPI service for the campus assistant. It provides intent routing, LangGraph orchestration, business-tool calls, knowledge retrieval, and structured fallback responses.

## Run

Create a local `.env` from `.env.example`, fill in model and service credentials, then install dependencies:

```powershell
python -m venv .venv
.\\.venv\\Scripts\\Activate.ps1
pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8001
```

Knowledge documents belong in `knowledge/`. Runtime indexes, checkpoints, and vector databases are generated under `data/` and intentionally ignored by Git.

## Tests

```powershell
python -m pytest
```
