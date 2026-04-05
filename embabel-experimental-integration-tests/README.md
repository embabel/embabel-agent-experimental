# ToolLoop Integration Tests

Demonstrates framework-agnostic `DefaultToolLoop` and `ParallelToolLoop` with different LLM backends.

## Setup

```bash
export OPENAI_API_KEY=your_api_key_here
```

## Tests

Tests retrieve restaurant menus as tools and provide restaurant recommendations based upon menus.

| Test Name | Description                                            | What to Watch in Logs                                                         |
|-----------|--------------------------------------------------------|-------------------------------------------------------------------------------|
| `DefaultToolLoopIntegrationTest` | Spring AI integration with inspectors and transformers | `beforeLlmCall`, `afterLlmCall`, `afterToolResult` callbacks; truncation logs |
| `ParallelToolLoopIntegrationTest` | Parallel tool execution via `ParallelToolLoop`         | Tools execution timing and thread names showing concurrent execution          |
| `LangChainToolLoopIntegrationTest` | LangChain4j SDK direct proving framework independence  | Same callbacks work with LangChain4j; tool invocations tracked                |
| `OpenAiToolLoopIntegrationTest` | OpenAI Java SDK direct (no Spring AI)                  | Iteration 1 tool calls, Iteration 2 final response; API usage stats           |

## Key Classes

- `AbstractToolLoopTest` - Shared utilities: menu tools, transformers, logging inspector
- `LangChain4jLlmMessageSender` - LangChain4j implementation of `LlmMessageSender`
- `OpenAiLlmMessageSender` - OpenAI SDK implementation of `LlmMessageSender`

## Run Tests

```bash

mvn -Dtest=*IT -Dsurefire.failIfNoSpecifiedTests=false test

```
