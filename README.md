# QiLu LifeServer

Campus service platform composed of a Spring Boot business service, Java AI API/provider modules, a Python AI agent, an RPC framework, and a static web frontend.

## Modules

- `QiLu-LifeServer`: core campus backend and REST/WebSocket APIs.
- `qilu-ai-api`: shared AI DTOs and RPC interfaces.
- `qilu-ai-service`: Java AI provider service.
- `qilu-ai-agent`: Python FastAPI agent with retrieval and tool orchestration.
- `qilu-rpc-framework-ai`: RPC core and Spring Boot starter.
- `redis_front`: static campus frontend assets.

## Configuration

Copy `.env.example` files to local environment files and provide database, Redis, message broker, model, and internal service credentials through environment variables. Credentials and runtime data are intentionally excluded from this repository.

## Build

```powershell
mvn -f qilu-rpc-framework-ai/pom.xml clean test
mvn -f QiLu-LifeServer/pom.xml test
mvn -f qilu-ai-service/pom.xml test
```
