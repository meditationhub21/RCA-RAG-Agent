# RCA Agent MVP

A Java 21 / Spring Boot service that indexes a local Java repository and investigates uploaded or supplied error logs using source, graph, Git, configuration, and semantic evidence. The request stays read-only with respect to the target repository; suggested fixes are returned for review.

## RCA flow

`POST /rca` resolves the first repository-owned stack frame, builds a bounded serializable incident context, and analyzes the error log together with source, graph and Git context, Maven/Gradle dependency declarations, and optional fresh runtime metrics. The core reasoner does not classify errors through a list of exception-specific handlers: it follows the exception chain, anchors the report to the application frame and source statement, correlates matching dependency names and measurements, and reports what remains unproven. This works for unfamiliar error types and libraries without adding a handler for each one.

Mandatory Spring AI LLM synthesis receives only the bounded context packet and turns the collected evidence into a causal explanation and code/configuration remediation. It has no source filesystem, shell, or database tools. Returned evidence IDs are restricted to IDs already present in the packet and model confidence is capped by context quality. If the model request fails or returns no valid evidence references, the RCA request fails clearly; the service does not return a heuristic-only diagnosis.

## Endpoints

- `POST /repositories/sync` JSON: `{"repositoryName":"petclinic","sourceDirectory":"C:\\path\\to\\petclinic"}`
- `POST /repositories/telemetry` JSON: sends a bounded latest runtime metric snapshot for a repository.
- `POST /rca` JSON: `{"repositoryName":"petclinic","errorMessage":"java.lang.NullPointerException: ...","stackTrace":"..."}`
- `POST /rca` multipart: fields `repositoryName` and `file` (a text log).

The RCA response retains `repository`, `rootCause`, `evidence`, `fixRecommendation`, `confidence`, `similarIncidents`, and `limitation`; it now also returns `reasoning`, `missingInformation`, `nextInvestigation`, and `likelyIntroducingCommit`. `rootCause` includes `exceptionType`, `suspectedExpression`, and `variable` in addition to the original location fields.

## Run locally

Requirements: JDK 21 and Maven 3.9+.

```powershell
mvn spring-boot:run
```

The application requires Neo4j, PostgreSQL with pgvector, and a local Ollama server. The Compose Ollama service mounts `./models` read-only at `/models`. Copy your GGUF files into the `models` directory at the project root with these exact filenames:

```text
models/gemma-4-31B_q4_0-it.gguf
models/nomic-embed-text-v1.5.Q4_0.gguf
```

The GGUF files are ignored by Git. Then start all three services:

```powershell
docker compose up -d neo4j postgres-vector ollama
```

Import the GGUF files into Ollama once. Ollama stores the imported model data in its persistent `ollama_data` volume, so the GGUF source files can remain in `./models` for later rebuilds:

```powershell
docker compose exec ollama ollama --version
docker compose exec ollama ollama create rca-gemma4-31b -f /models/Modelfile.gemma4
docker compose exec ollama ollama create rca-nomic-embed-v1.5 -f /models/Modelfile.nomic-embed-v1.5
docker compose exec ollama ollama list
docker compose ps
```

The defaults are Neo4j at `bolt://localhost:7687` (`neo4j/change-this-password`), PostgreSQL at `localhost:5432` (`rca/rca`), and Ollama at `http://localhost:11434`. Verify the GGUF imports with a direct chat request and an embedding request before starting the app. GGUF support depends on the Ollama build supporting each model architecture; the files are not included in this repository.

```powershell
docker compose exec ollama ollama run rca-gemma4-31b "Reply with READY."
$probe = @{ model = 'rca-nomic-embed-v1.5'; input = 'RCA embedding compatibility probe' } | ConvertTo-Json
$embedding = Invoke-RestMethod -Method Post -Uri 'http://localhost:11434/api/embed' -ContentType 'application/json' -Body $probe
$embedding.embeddings[0].Count
```

The embedding probe should report `768`, matching `RCA_VECTOR_EMBEDDING_DIMENSIONS`. If the GGUF model imports but the embedding endpoint fails, do not start a repository sync yet; resolve that Ollama/model compatibility error first.

Graph persistence includes repository/file/type/method relationships and `RCA_CASE` records. The app retains the latest repository snapshot in process memory, so sync the repository again after restarting the app and after source edits. Git blame/history queries read the repository during an investigation, but source evidence comes from the last synchronized snapshot.

### Required local LLM and vector database

The app uses Spring AI `ChatClient` with the imported local `rca-gemma4-31b` GGUF for RCA synthesis and `rca-nomic-embed-v1.5` for local embeddings stored in mandatory PostgreSQL/pgvector. No OpenAI API key or paid inference credits are required. The models are configurable with `OLLAMA_CHAT_MODEL`, `OLLAMA_EMBEDDING_MODEL`, and `OLLAMA_BASE_URL`. Nomic v1.5 uses `search_document: ` for indexed text and `search_query: ` for RCA retrieval queries. Its configured output dimension is 768; set `RCA_VECTOR_EMBEDDING_DIMENSIONS` to the selected model's output dimension when changing the embedding model. The prompt context is bounded and chat generation is capped at 512 tokens for this local setup.

```powershell
mvn spring-boot:run
```

Startup requires PostgreSQL/pgvector. Repository syncs that need embeddings require the configured Nomic model, and RCA requests require the configured Gemma model. Gemma 4 31B Q4_0 is much larger than the previous Qwen 1.7B model. CPU-only inference can be extremely slow and requires substantial RAM; GPU acceleration requires enough available VRAM and working Docker GPU passthrough. Review the actual GGUF file size and Docker resource allocation before loading it.

The application creates the pgvector extension, an embedding-model-specific vector table, and an HNSW index on startup. Repository sync embeds changed files and methods in batches; RCA caches the repeated query embedding and stores incidents for later retrieval. This model pairing uses `.rca-index-gemma4-nomic-v1.5/` so the first sync rebuilds vectors using the new Nomic GGUF instead of treating the old model's manifest as current. The app retains the latest repository snapshot in process memory, so sync the repository again after restarting the app and after source edits.

### Repository path restriction

Set `REPOSITORY_ALLOWED_ROOT` to a parent directory to reject sync requests outside that canonical path. It is unset by default. Sync hash manifests are saved below the application working directory in `.rca-index-ollama/`, not inside the target repository.

## Demo

```powershell
$repoPath = (Resolve-Path .\examples\petclinic).Path
$syncBody = @{ repositoryName = 'petclinic'; sourceDirectory = $repoPath } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/repositories/sync -ContentType 'application/json' -Body $syncBody
```

For a file upload in Git Bash:

```bash
curl --fail-with-body -sS -X POST 'http://localhost:8080/rca' \
  -F 'repositoryName=petclinic' \
  -F 'file=@/c/path/to/npe-error.log;type=text/plain'
```

The upload handler extracts the exception name/message and passes the complete file contents as the stack trace.

### Runtime metrics and dependency declarations

Sync discovers declared dependency coordinates from Maven POMs, Gradle Groovy/Kotlin build scripts, and Gradle version catalogs. These declarations provide investigation context; optional dependencies, profiles, and runtime switches mean they do not prove a component is active in production.

Build files cannot report live CPU, heap, Hikari pool saturation, Kafka consumer lag, Redis health, or RabbitMQ queue depth. The RCA service accepts measurements from Micrometer, OpenTelemetry, Prometheus adapters, or another collector through a generic contract. Only fresh snapshots (10 minutes by default) are attached to RCA evidence. For example:

```json
{
  "repositoryName": "petclinic",
  "source": "micrometer",
  "metrics": {
    "process.cpu.usage": 0.82,
    "jvm.memory.used": 734003200,
    "jvm.memory.max": 1073741824,
    "hikaricp.connections.active": 18,
    "hikaricp.connections.max": 20,
    "hikaricp.connections.pending": 4
  },
  "units": {
    "process.cpu.usage": "ratio",
    "jvm.memory.used": "bytes",
    "jvm.memory.max": "bytes"
  },
  "labels": { "instance": "petclinic-1", "environment": "dev" }
}
```

Send it to `POST http://localhost:8080/repositories/telemetry`. This generic endpoint accepts externally collected data; automatic scraping of each target application's metrics endpoint is not configured by default. Without a fresh telemetry snapshot, RCA explicitly reports that live resource/pool/broker health is unavailable rather than inferring it from dependency declarations.

## Scope and current limits

The service does not include a UI, webhooks, automatic source changes, or an autonomous multi-step agent. Runtime telemetry can be ingested, but target-app instrumentation and automatic scraping are not configured by default. Graph matching relies on parsed names and relationships and can be incomplete in large or dynamically wired applications. Every RCA response requires successful Spring AI model synthesis and pgvector embedding/retrieval. The Neo4j and pgvector integrations require their external services to be running.
