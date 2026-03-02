# Embabel Agent Claude Code

Claude Code CLI integration for the Embabel Agent framework. Provides a thin wrapper around the
[Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code) for executing agentic coding tasks
from JVM applications, with support for typed structured output, fitness evaluation, tool exposure via MCP,
and optional Docker sandboxing.

## Prerequisites

Claude Code CLI must be installed and available in the PATH:

```bash
npm install -g @anthropic-ai/claude-code
```

## Maven Dependency

```xml
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-claude-code</artifactId>
</dependency>
```

## Core Components

| Class | Purpose |
|---|---|
| `ClaudeCodeExecutor` | Executes the `claude` CLI with structured JSON output parsing |
| `AgentRequest` | Typed request with fitness evaluation and retry |
| `ClaudeCodeTool` | Exposes Claude Code as a `Tool` for other LLMs to invoke |
| `CodeImplementationAgent` | Embabel `@Agent` with high-level coding actions |
| `EphemeralMcpToolServer` | Spins up a temporary MCP SSE server to expose tools to Claude |

## Usage

### Direct Execution

The simplest way to use Claude Code from Java:

```java
var executor = new ClaudeCodeExecutor();

ClaudeCodeResult result = executor.execute(
    "Add a fibonacci function to MathUtils.java",
    Path.of("/path/to/project"),
    List.of(ClaudeCodeAllowedTool.READ, ClaudeCodeAllowedTool.EDIT),
    20,                                           // maxTurns
    ClaudeCodePermissionMode.ACCEPT_EDITS,
    Duration.Companion.minutes(5),
    null,                                         // sessionId
    null,                                         // model
    null,                                         // systemPrompt
    false,                                        // streamOutput
    null,                                         // streamCallback
    null                                          // mcpConfig
);

if (result instanceof ClaudeCodeResult.Success success) {
    System.out.println("Done: " + success.getResult());
    System.out.println("Cost: $" + success.getCostUsd());
    System.out.println("Files changed: " + success.getAllAffectedFiles());
} else if (result instanceof ClaudeCodeResult.Failure failure) {
    System.err.println("Failed: " + failure.getError());
}
```

### Typed Execution with AgentRequest

`AgentRequest` provides structured output parsing with fitness evaluation and automatic retry.
Use the `withX()` methods for a fluent Java API:

```java
// Define the output type
public record AnalysisReport(
    String summary,
    List<String> issues,
    int severity
) {}

var executor = new ClaudeCodeExecutor();

// Build the request fluently
AgentRequest<AnalysisReport> request = AgentRequest.fromPrompt(
        () -> "Analyze src/main/java for potential null pointer issues. " +
              "Return a JSON report with summary, issues list, and severity 1-10."
    )
    .returning(AnalysisReport.class)
    .withMaxRetries(2)
    .withFitnessThreshold(0.7)
    .withFitnessFunction(report ->
        report.issues().isEmpty() ? 0.5 : 1.0
    );

TypedResult<AnalysisReport> result = executor.executeTyped(request);

if (result instanceof TypedResult.Success<AnalysisReport> success) {
    AnalysisReport report = success.getValue();
    System.out.println("Summary: " + report.summary());
    System.out.println("Issues: " + report.issues().size());
    System.out.println("Fitness score: " + success.getScore());
    System.out.println("Attempts: " + success.getAttempts());
}
```

### Exposing Tools to Claude via MCP

Pass tool objects to `AgentRequest` and they will be exposed to Claude Code
via an ephemeral MCP SSE server. Tools can be objects with `@LlmTool` annotations,
`Tool` instances, or Spring AI `ToolCallback` instances:

```java
// A tool object with @LlmTool annotations
public class DatabaseLookup {
    @LlmTool(description = "Look up a customer by ID")
    public String lookupCustomer(String customerId) {
        return customerRepo.findById(customerId).toString();
    }
}

var request = AgentRequest.fromPrompt(
        () -> "Find customer C-123 and summarize their recent orders."
    )
    .returning(CustomerSummary.class)
    .withTool(new DatabaseLookup())
    .withTool(new OrderHistoryTool());

TypedResult<CustomerSummary> result = executor.executeTyped(request);
```

Multiple tools can be added at once:

```java
var request = AgentRequest.fromPrompt(() -> "...")
    .returning(Result.class)
    .withToolObjects(new DatabaseLookup(), new SlackNotifier(), new JiraClient());
```

### Fitness Functions

Fitness functions evaluate output quality and drive retry logic.
The score is a `double` between 0.0 and 1.0:

```java
// Compose fitness functions
FitnessFunction<Report> hasSummary = report ->
    report.summary() != null && !report.summary().isBlank() ? 1.0 : 0.0;

FitnessFunction<Report> hasIssues = report ->
    report.issues() != null && !report.issues().isEmpty() ? 1.0 : 0.5;

// Combine with AND (minimum) — both must score high
var combined = FitnessFunctionsKt.allOf(hasSummary, hasIssues);

// Or use weighted combination
var weighted = FitnessFunctionsKt.weightedFitness(
    new Pair<>(0.7, hasSummary),
    new Pair<>(0.3, hasIssues)
);

var request = AgentRequest.fromPrompt(() -> "...")
    .returning(Report.class)
    .withFitnessFunction(combined)
    .withMaxRetries(3)
    .withFitnessThreshold(0.8);
```

### Sandboxed Execution

Run Claude Code inside a Docker container for isolation:

```java
// Use the built-in sandboxed factory
var executor = ClaudeCodeExecutor.sandboxed(
    "embabel/claude-code-sandbox:latest",
    Duration.Companion.minutes(10)
);

// Or with DANGEROUSLY_SKIP_PERMISSIONS for fully unattended CI/CD
var ciExecutor = new ClaudeCodeExecutor(
    "claude",
    Duration.Companion.minutes(10),
    ClaudeCodePermissionMode.DANGEROUSLY_SKIP_PERMISSIONS,
    Map.of(),
    new DockerExecutor("embabel/claude-code-sandbox:latest", true, "2g", "2.0")
);
```

### Streaming

Monitor Claude Code's progress in real time:

```java
ClaudeCodeResult result = executor.execute(
    "Refactor the authentication module",
    projectPath,
    null,  // allowedTools
    30,    // maxTurns
    ClaudeCodePermissionMode.ACCEPT_EDITS,
    Duration.Companion.minutes(10),
    null,  // sessionId
    null,  // model
    null,  // systemPrompt
    true,  // streamOutput
    event -> {
        if (event instanceof ClaudeStreamEvent.Text text) {
            System.out.println("[Claude] " + text.getText());
        } else if (event instanceof ClaudeStreamEvent.ToolResult tool) {
            System.out.println("[Tool] " + tool.getToolId());
        } else if (event instanceof ClaudeStreamEvent.Complete done) {
            System.out.println("Done in " + done.getTurns() + " turns, $" + done.getCostUsd());
        } else if (event instanceof ClaudeStreamEvent.Error err) {
            System.err.println("Error: " + err.getMessage());
        }
    },
    null   // mcpConfig
);
```

### Async Execution

Run Claude Code without blocking:

```java
ClaudeCodeAsyncExecution async = executor.executeAsync(
    "Refactor the authentication module",
    projectPath
);

// Poll or do other work
while (async.isRunning()) {
    System.out.println("Still running...");
    Thread.sleep(5000);
}

ClaudeCodeResult result = async.await();

// Or use CompletableFuture
async.toFuture().thenAccept(r -> {
    if (r instanceof ClaudeCodeResult.Success s) {
        System.out.println("Done: " + s.getResult());
    }
});
```

### Session Continuations

Resume a previous Claude Code session for multi-step workflows:

```java
var agent = new CodeImplementationAgent(executor);
var codebase = new Codebase(Path.of("/project"), "java", "spring");

// Step 1: Fix the bug
ImplementationOutcome fix = agent.fixBug(
    "NullPointerException in UserService.findById",
    codebase,
    List.of("1. Call GET /users/999", "2. Observe NPE in logs")
);

// Step 2: Create a PR from the same session
if (fix.getSuccess() && fix.getSessionId() != null) {
    agent.createPullRequest(
        fix.getSessionId(),
        codebase,
        42,       // issue number
        "main"    // base branch
    );
}
```

### As a Tool for Other LLMs

Expose Claude Code as a tool that other LLMs can invoke:

```java
// Full-featured tool
ClaudeCodeTool tool = new ClaudeCodeTool(
    executor,
    Path.of("/project"),    // default working directory
    null,                   // default allowed tools
    20                      // default max turns
);

// Read-only exploration tool
ClaudeCodeTool readOnlyTool = ClaudeCodeTool.readOnly(executor, Path.of("/project"));

// Use with PromptRunner
context.ai()
    .withTool(tool)
    .generateText("Find and fix the bug in UserService");
```
