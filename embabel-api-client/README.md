# Embabel API Client

**Smart client that learns APIs from their specs and materializes tools at runtime.**

## Core Idea

The conventional approach to making APIs available to LLMs puts the burden on the server:
someone has to write tool wrappers, deploy MCP servers, and maintain them.
When APIs are large, tools proliferate and overwhelm the LLM's context.

Embabel API Client flips this. **The smarts are on the client.**

1. **Point at a spec** — an OpenAPI document, a GraphQL schema, HATEOAS links, or any machine-readable API description.
2. **Learn the API** — parse the spec, extract operations, parameters, auth requirements.
3. **Satisfy auth** — the learner tells you what credentials are needed; you provide them.
4. **Get a tool** — a `ProgressiveTool` that organizes all operations via `UnfoldingTool`, enabling progressive disclosure to the LLM.

```
                        ┌─────────────────┐
   OpenAPI Spec ──────▶ │                 │          ┌─────────────┐
   GraphQL Schema ────▶ │  ApiLearner     │──learn───▶ LearnedApi  │
   HATEOAS Links ─────▶ │  (learns API)   │          │ - name      │
   Spring Repos ──────▶ │                 │          │ - auth reqs │
                        └─────────────────┘          │ - create()  │──▶ ProgressiveTool
                                                     └─────────────┘     ├── pets (4 tools)
                                                                         ├── store (1 tool)
                                                                         └── users (1 tool)
```

### Why not MCP?

| | MCP | Embabel API Client |
|---|---|---|
| **Who does the work?** | Server author wraps each endpoint | Client learns from the spec |
| **Deployment** | Separate MCP server per API | No deployment — just a spec URL |
| **Tool proliferation** | Flat list of tools | `UnfoldingTool` tree with progressive disclosure |
| **Auth** | Each server handles its own | Consistent `AuthRequirement` / `ApiCredentials` contract |
| **Multiple APIs** | Multiple MCP servers | Multiple learners, same caller code |

MCP is great when you need custom server-side logic. But for standard APIs that already describe themselves via OpenAPI (or GraphQL, etc.), there's no need to rewrite that description as an MCP server. The spec *is* the tool definition.

## Usage

### OpenAPI

```kotlin
val learner = OpenApiLearner()

// 1. Learn — learn the API and its auth requirements
val learned = learner.learn("https://petstore3.swagger.io/api/v3/openapi.json")

println(learned.name)              // "petstore"
println(learned.authRequirements)  // [ApiKey(name="api_key", location=HEADER)]

// 2. Create — satisfy auth and get a tool
val petstore = learned.create(ApiCredentials.ApiKey("my-key"))

// petstore is a ProgressiveTool — hand it directly to an agent
// LLM sees: "petstore" → invokes → sees categories [pet, store, user]
// → picks "pet" → sees [addPet, updatePet, findPetsByStatus, getPetById, deletePet]
// → calls getPetById with {"petId": 42}
```

### No auth required

```kotlin
val learned = learner.learn("https://api.example.com/openapi.json")
if (learned.authRequirements.all { it is AuthRequirement.None }) {
    val tool = learned.create() // no credentials needed
}
```

### Composing multiple APIs

```kotlin
val learner = OpenApiLearner()
val tools = listOf(
    learner.learn("https://api.stripe.com/spec").create(ApiCredentials.Token("sk-...")),
    learner.learn("https://api.github.com/openapi.json").create(ApiCredentials.Token("ghp_...")),
)
// Each API is a top-level ProgressiveTool. The LLM picks which API to explore.
```

## How It Works

1. **Learn** — `ApiLearner.learn()` parses the spec, extracts metadata, auth requirements, and captures enough to build tools later.
2. **Examine** — `LearnedApi` holds the API's name, description, and auth requirements. The caller can examine these before committing credentials.
3. **Create** — `LearnedApi.create(credentials)` materializes the full tool tree:
   - Each operation becomes an `OpenApiOperationTool` (a `Tool`)
   - Operations are grouped by tag via `UnfoldingTool.byCategory`
   - Credentials are applied to the underlying HTTP client
   - The result is a `ProgressiveTool` ready for an agent

## Auth Model

The learner tells you what the API needs. You provide it. The mapping is consistent across API types.

| Requirement | Credential | What happens |
|---|---|---|
| `AuthRequirement.None` | `ApiCredentials.None` | No auth applied |
| `AuthRequirement.ApiKey` | `ApiCredentials.ApiKey` | Key placed in header/query/cookie per spec |
| `AuthRequirement.Bearer` | `ApiCredentials.Token` | `Authorization: Bearer <token>` header |
| `AuthRequirement.OAuth2` | `ApiCredentials.Token` | `Authorization: Bearer <token>` header |

## Extending to Other API Types

The `ApiLearner` interface is consistent across API description formats. Each implementation handles a different source but the caller code is identical: learn, check auth, create.

| Source | Learner | Operations | Grouping |
|---|---|---|---|
| **OpenAPI** | `OpenApiLearner` | HTTP operations | Tags |
| **GraphQL** | `GraphQLLearner` (future) | Queries + Mutations | Type |
| **HATEOAS** | `HateoasLearner` (future) | Link relations | Discovered at runtime |
| **Spring Repositories** | `RepositoryLearner` (future) | CRUD methods | Entity type |

## Design Principles

- **Zero server-side work** — if the API describes itself, that's enough.
- **Client-side intelligence** — the agent decides how to organize and navigate tools.
- **Progressive disclosure** — `UnfoldingTool` keeps the LLM's tool context manageable regardless of API size.
- **Consistent auth contract** — `AuthRequirement` in, `ApiCredentials` out. Same for every API type.
- **Runtime, not build-time** — tools are created dynamically from specs. No code generation step.
- **No framework leakage** — the learner interface doesn't expose RestClient, HTTP details, or any implementation concern.
