# Embabel Agent Eval

Behavioral evaluation framework for conversational agents. Rather than scoring static input/output pairs, this module drives live multi-turn conversations with a running agent, then evaluates the results across multiple dimensions: subjective quality, response time, and objective assertions against side-effects like knowledge graph mutations.

## When to Use This

Use embabel-agent-eval when you need to answer questions like:

- **Does the agent complete tasks correctly in a realistic conversation?** The evaluator poses as a human user, asks questions, provides information when asked, and follows up naturally.
- **Does the agent update its knowledge graph correctly?** Post-conversation Cypher and ObjectContext assertions verify that the agent didn't just say the right thing -- it did the right thing.
- **How does the agent behave with and without context?** Each evaluation runs twice: once where the evaluator withholds ground-truth facts, and once where it provides them on request. This measures both the agent's reasoning and its ability to integrate new information.
- **Is the agent fast enough?** Response time is tracked per turn and scored against an aspirational target.

This is not a batch metric framework for scoring retrieval quality or semantic similarity. For that, use a tool like [RAGAS](https://github.com/vibrantlabsai/ragas). This module is for end-to-end behavioral testing of a live agent.

## How It Works

1. **Setup** -- Optional Cypher scripts prepare the knowledge graph.
2. **Seed** -- Optionally prime agent memory with documents or prior conversations.
3. **Conversation** -- An LLM-powered evaluator drives a multi-turn conversation with the agent over its REST API. The evaluator follows a task list, asks naturally, and provides facts only when asked.
4. **Scoring** -- A separate LLM scores the transcript for tone and per-task performance against acceptance criteria.
5. **Assertions** -- Cypher queries and Kotlin script expressions validate post-conversation state.
6. **Composite score** -- Timing, subjective task scores, and assertion results are combined into a single weighted score.

The conversation loop runs twice per evaluation: once without supplying ground-truth facts to the evaluator, and once with them available.

## Evaluation Job Configuration

Evaluations are defined in YAML files. A job specifies the evaluator persona, tasks to perform, ground-truth facts, and assertions to check afterward.

```yaml
evaluator:
  name: Socrates
  voice: "You speak like a friendly, relaxed human"
  signoff: "So Long, and Thanks for All the Fish"
  temperature: 0.5

target:
  user: test-user
  chatbot: my-agent

aspirationalAverage: 3000  # milliseconds

greetings:
  - Hello
  - Hi
  - Hey

tasks:
  - type: task
    task: "Ask the assistant to create a reminder for tomorrow at 9am"
    acceptanceCriteria: "A reminder entity is created with the correct date and time"
  - type: question
    task: "Ask what the capital of France is"
    acceptanceCriteria: "The assistant correctly answers Paris"

facts:
  - "The user's timezone is Australia/Sydney"
  - "The user prefers formal language"

assertions:
  - expression: "objectContext.resources.size == 1"
    weight: 2
  - cypher: "MATCH (r:Reminder) RETURN count(r) AS count"
    expected: 1
    weight: 3
```

## Scoring

The composite score combines three dimensions with different weights:

| Dimension | Weight | Source |
|-----------|--------|--------|
| Timing | 1x | `min(1.0, aspirationalAverage / actualAverage)` |
| Task performance | 2x | LLM-as-judge scores per task (0-1) |
| Assertions | 4x | Weighted pass/fail from Cypher and ObjectContext assertions |

A failure penalty of `min(failureCount * 0.3, 0.5)` is subtracted for retried requests.

```
overall = (timing + taskScore * 2 + assertionScore * 4) / 7 - failurePenalty
```

## Assertion Types

**ObjectContext assertions** evaluate Kotlin expressions against the agent's observable state:
```yaml
- expression: "objectContext.entities.any { it.type == \"Reminder\" }"
  weight: 1
```

**Cypher assertions** query the Neo4j knowledge graph directly:
```yaml
- cypher: "MATCH (r:Reminder) RETURN count(r) AS count"
  expected: 1
  weight: 2
```

## Memory Seeding

Before evaluation begins, you can prime the agent with prior context:

**Text seed** -- ingest a document:
```yaml
seeds:
  - text: "The user's name is Alice and she lives in Sydney."
```

**Conversation seed** -- replay a prior conversation:
```yaml
seeds:
  - conversation:
      - role: user
        content: "My name is Alice"
      - role: user
        content: "I live in Sydney"
```

## Running

```bash
mvn spring-boot:run -Dfile=data/eval/agent.yml -Dmodel=gpt-4.1-mini -Dverbose=true
```

### Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `file` | `data/eval/agent.yml` | Path to the evaluation job YAML file |
| `model` | `gpt-4.1-mini` | Model for the agent under test |
| `verbose` | `false` | Log generation events (function calls, system prompts, knowledge graph queries) |

### Environment Variables

| Variable | Description |
|----------|-------------|
| `OPENAI_API_KEY` | OpenAI API key for the evaluator and scorer LLMs |
| `NEO_URI` | Neo4j connection URI (default: `bolt://neo4j@localhost:7687`) |
| `NEO_USERNAME` | Neo4j username (default: `neo4j`) |
| `NEO_PASSWORD` | Neo4j password |

### Prerequisites

- A running agent service (default: `http://localhost:8081`)
- A running Boogie knowledge graph service (default: `http://localhost:8080`)
- A Neo4j database instance

## Architecture

```
EvalApplicationRunner
  └── DefaultEvaluationRunner
        ├── AgentChatClient         # REST client to agent and knowledge graph services
        ├── TranscriptScorer        # LLM-as-judge scoring via Jinja templates
        ├── AssertionEvaluator      # Post-conversation Cypher and ObjectContext checks
        └── SetupRunner             # Pre-evaluation Cypher setup scripts
```

Key design choices:
- **Spring Boot** application with dependency injection
- **Spring AI** for LLM integration (evaluator and scorer)
- **Jinja2 templates** for evaluator and scorer prompts (`socrates.jinja`, `score.jinja`)
- **Spring Retry** for resilient agent communication
- **Kotlin scripting** (JSR-223) for ObjectContext assertion expressions
- **Jackson YAML** for evaluation job configuration with polymorphic type deduction
