# Knowledge Base Platform - Architecture Design

## Overview

RAG (Retrieval-Augmented Generation) knowledge base platform that ingests knowledge from Confluence and other sources into Qdrant vector database, provides intelligent Q&A for engineers, and integrates with AppDynamics/Splunk monitoring to deliver automated incident insights.

## Architecture Diagram

```mermaid
graph TB
    subgraph "Data Sources"
        CF[Confluence API]
        WIKI[Internal Wiki/Docs]
        RB[Runbooks/Playbooks]
    end

    subgraph "Ingestion Pipeline"
        SYNC[Knowledge Sync Service<br/>Scheduled + Webhook]
        CHUNK[Document Chunker<br/>Recursive Text Splitter]
        EMBED_I[Embedding Service<br/>text-embedding-3-large]
    end

    subgraph "Vector Store"
        QD[(Qdrant<br/>Vector Database)]
        QD_COL1[Collection: confluence_docs]
        QD_COL2[Collection: incident_resolutions]
        QD_COL3[Collection: runbooks]
    end

    subgraph "Monitoring Integration"
        APPD[AppDynamics]
        SPL[Splunk]
        N8N[n8n Workflow Engine]
        ALERT[Alert Processor]
    end

    subgraph "Persistence"
        DDB[(DynamoDB<br/>Alert Insights)]
    end

    subgraph "Core Backend - Spring Boot"
        API[REST API Gateway]
        RAG[RAG Query Engine]
        EMBED_Q[Query Embedding]
        LLM[GPT-5.1 LLM Client]
        CACHE[(Redis Cache)]
    end

    subgraph "Frontend - React"
        CHAT[Chat Interface]
        SEARCH[Knowledge Search]
        INSIGHTS[Alert Insights Panel]
        ADMIN[Admin / Sync Dashboard]
    end

    CF --> SYNC
    WIKI --> SYNC
    RB --> SYNC
    SYNC --> CHUNK
    CHUNK --> EMBED_I
    EMBED_I --> QD

    QD --> QD_COL1
    QD --> QD_COL2
    QD --> QD_COL3

    APPD -->|Webhook| N8N
    SPL -->|Webhook| N8N
    N8N -->|POST /api/v1/kb/alert-analyze| ALERT
    ALERT --> RAG
    ALERT -->|Persist| DDB

    CHAT --> API
    SEARCH --> API
    INSIGHTS -->|GET /alerts| API
    INSIGHTS -->|Read| DDB
    ADMIN --> API

    API --> RAG
    RAG --> EMBED_Q
    EMBED_Q --> QD
    RAG --> LLM
    RAG --> CACHE
```

## Data Flow: Knowledge Ingestion

```mermaid
sequenceDiagram
    participant CF as Confluence
    participant SYNC as Sync Service
    participant CHUNK as Chunker
    participant EMB as Embedding API
    participant QD as Qdrant

    SYNC->>CF: GET /rest/api/content (paginated)
    CF-->>SYNC: Pages + metadata
    SYNC->>SYNC: Detect changes (CQL lastModified)
    SYNC->>CHUNK: Split document (1000 tokens, 200 overlap)
    CHUNK-->>SYNC: Chunks with metadata
    loop Each chunk batch (50)
        SYNC->>EMB: POST embeddings (batch)
        EMB-->>SYNC: Vector[3072]
        SYNC->>QD: Upsert points with payload
    end
```

## Data Flow: Q&A Query (RAG)

```mermaid
sequenceDiagram
    participant USER as Engineer
    participant FE as React Frontend
    participant API as Spring Boot API
    participant CACHE as Redis
    participant EMB as Embedding API
    participant QD as Qdrant
    participant LLM as GPT-5.1

    USER->>FE: Ask question
    FE->>API: POST /api/v1/kb/query
    API->>CACHE: Check cache (query hash)
    alt Cache hit
        CACHE-->>API: Cached response
    else Cache miss
        API->>EMB: Embed query
        EMB-->>API: Query vector
        API->>QD: Search (top-k=5, score_threshold=0.75)
        QD-->>API: Relevant chunks + metadata
        API->>LLM: Prompt = system_prompt + context_chunks + question
        LLM-->>API: Generated answer with citations
        API->>CACHE: Cache response (TTL=1h)
    end
    API-->>FE: Answer + sources + confidence
    FE-->>USER: Render answer with source links
```

## Data Flow: Monitoring Alert Analysis (n8n + DynamoDB)

```mermaid
sequenceDiagram
    participant APPD as AppDynamics/Splunk
    participant N8N as n8n Workflow
    participant API as KB API
    participant QD as Qdrant
    participant LLM as GPT-5.1
    participant DDB as DynamoDB
    participant SLACK as Slack/Teams
    participant FE as React Frontend

    APPD->>N8N: Alert webhook (error_msg, app, severity)
    N8N->>N8N: Enrich: parse error, extract stacktrace
    N8N->>API: POST /api/v1/kb/alert-analyze
    API->>QD: Vector search (error signature)
    QD-->>API: Similar past incidents + runbook chunks
    API->>LLM: "Given this error and context, provide RCA + actions"
    LLM-->>API: Insights + recommended actions
    API->>DDB: PutItem (alertId, source, RCA, actions, severity)
    API-->>N8N: Analysis result
    N8N->>SLACK: Post formatted insight card

    Note over FE,DDB: Frontend reads from DynamoDB via API
    FE->>API: GET /api/v1/kb/alerts?source=appd
    API->>DDB: Query by source (newest first)
    DDB-->>API: Alert insight records
    API-->>FE: Alert history list
    FE->>API: PATCH /alerts/{source}/{id}/status?status=RESOLVED
    API->>DDB: UpdateItem (status=RESOLVED)
```

## n8n Workflow Design

```mermaid
graph LR
    A[Webhook Trigger<br/>AppD/Splunk] --> B[Parse Alert<br/>Extract Fields]
    B --> C{Severity?}
    C -->|Critical/High| D[Call KB API<br/>alert-analyze]
    C -->|Low/Medium| E[Queue for<br/>batch analysis]
    D --> F[Format Response<br/>Markdown Card]
    F --> G[Post to Slack<br/>#incident-channel]
    F --> H[Create JIRA Ticket<br/>with insights]
    E --> I[Batch Analyze<br/>every 15min]
    I --> F
```

## Component Details

### Qdrant Collections Schema

| Collection | Vector Dim | Payload Fields | Distance |
|---|---|---|---|
| `confluence_docs` | 3072 | page_id, space_key, title, url, last_updated, chunk_index | Cosine |
| `incident_resolutions` | 3072 | incident_id, error_type, app_name, resolution, resolved_date | Cosine |
| `runbooks` | 3072 | runbook_id, title, category, steps, owner_team | Cosine |

### Tech Stack

| Layer | Technology |
|---|---|
| Frontend | React 18 + TypeScript + Ant Design |
| Backend | Java 21 + Spring Boot 3.2 |
| Vector DB | Qdrant (self-hosted or cloud) |
| LLM | GPT-5.1 (OpenAI API) |
| Embedding | text-embedding-3-large (3072 dim) |
| Cache | Redis |
| Workflow | n8n (self-hosted) |
| Monitoring Sources | AppDynamics, Splunk |
| Notifications | Slack / Microsoft Teams |

### API Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/kb/query` | Ask a question, get RAG answer |
| POST | `/api/v1/kb/alert-analyze` | Analyze alert, persist to DynamoDB |
| GET | `/api/v1/kb/alerts` | List alert insights from DynamoDB (filter by source/severity) |
| GET | `/api/v1/kb/alerts/{source}/{alertId}` | Get single alert detail |
| PATCH | `/api/v1/kb/alerts/{source}/{alertId}/status` | Update alert status (ACK/RESOLVED) |
| POST | `/api/v1/kb/ingest/confluence` | Trigger Confluence sync |
| GET | `/api/v1/kb/search` | Semantic search without LLM generation |
| GET | `/api/v1/kb/sources` | List ingested sources |
| GET | `/api/v1/kb/sync-status` | Check ingestion pipeline status |
| DELETE | `/api/v1/kb/collection/{name}` | Clear a collection |
