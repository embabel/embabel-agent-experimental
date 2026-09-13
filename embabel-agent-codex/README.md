# Embabel Agent Codex

Experimental Spring AI `ChatModel` for a Codex-enabled ChatGPT subscription.
It uses OAuth credentials and the Codex Responses endpoint, not an OpenAI API key.

## Dependency

Import the experimental BOM, then add:

```xml
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-codex</artifactId>
</dependency>
```

## Authentication

The module keeps its own credential file. Importing Codex CLI credentials is an
explicit bootstrap operation and does not modify `~/.codex/auth.json` on disk:

```kotlin
val store = FileCodexAuthStore(
    Path.of(System.getProperty("user.home"), ".embabel", "codex-auth.json"),
    jacksonObjectMapper(),
)
check(store.load() != null || store.importFromCodexCli())
```

OAuth refresh tokens may be rotated. After import, treat the Embabel store as the
credential owner; continuing to refresh from both Embabel and Codex CLI can
invalidate one session. Re-authenticate the CLI before using it again, or use the
device flow below to create a dedicated Embabel session.

For device login:

```kotlin
val deviceClient = CodexDeviceCodeClient()
val challenge = deviceClient.requestUserCode()
println("Open ${challenge.verificationUrl} and enter ${challenge.userCode}")
val credentials = deviceClient.exchange(deviceClient.loginInteractive(challenge))
store.save(credentials)
```

Applications decide how to display the verification URL and code.

If the token endpoint reports `invalid_refresh_token` or `refresh_token_reused`,
repeat device login to replace the Embabel session. Changing the model cannot fix
an authentication failure. The default chat retry policy does not retry
`CodexAuthException`; applications can provide their own retry policy.

Share one `CodexAccessTokenProvider` within an application. Its refresh lock is
per instance, not cross-process: do not run multiple processes against the same
credential file.

## ChatModel

```kotlin
val credentials = requireNotNull(store.load())
val tokenProvider = CodexAccessTokenProvider(store, CodexTokenRefresher())
val responsesClient = CodexResponsesClient(tokenProvider, credentials)
val chatModel = CodexChatModel(
    responsesClient = responsesClient,
    model = requireNotNull(System.getenv("EMBABEL_CODEX_MODEL")),
)

val response = chatModel.call(Prompt("Hello from Embabel"))
```

Set `EMBABEL_CODEX_MODEL` to a model available to the authenticated subscription.
The module does not publish a fixed model catalog.

## Thinking levels

Set Codex reasoning effort on default options or per prompt:

```kotlin
val options = CodexChatOptions(reasoningEffort = CodexReasoningEffort.HIGH)
val response = chatModel.call(Prompt("Explain this algorithm", options))
val lowerEffort = options.mutate().reasoningEffort(CodexReasoningEffort.LOW).build()
```

For Embabel's provider options conversion:

```kotlin
val llmOptions = LlmOptions(model = "your-model-id")
    .withCodexReasoningEffort(CodexReasoningEffort.HIGH)
```

The wire values are `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, and `max`. These are sent as `reasoning.effort`. An unset effort omits
`reasoning` and uses the server default. Runtime effort overrides the model's
configured default; `NONE` explicitly requests `none`, whereas null inherits
that default. Model support is determined by the subscription endpoint; the
module does not silently downgrade unsupported efforts. A generic thinking token
budget is not converted to effort because the two controls are not equivalent.

`mutate()`, builder cloning, and `combineWith()` preserve Codex effort. The builder
extends Spring AI's `DefaultChatOptionsBuilder` for portable option handling.

To run a live matrix in one Maven invocation (sequential requests):

```bash
EMBABEL_LIVE_CODEX=1 \
EMBABEL_CODEX_MODEL=gpt-5.6-sol,gpt-5.6-terra,gpt-5.6-luna,gpt-6-astra \
EMBABEL_CODEX_REASONING_EFFORT=low,medium,high,xhigh,max \
  mvn -pl embabel-agent-codex -Dtest=CodexLiveIT test
```

Verified on 2026-09-13 with this subscription: Sol, Terra and Luna accepted
`none`, `low`, `medium`, `high`, `xhigh`, `max`; Astra accepted `low`, `medium`,
`high`, `xhigh`, `max`. All four rejected `minimal`. The endpoint rejected
`ultra` as an invalid wire value, so it is deliberately not in this enum even
when a Codex UI exposes an Ultra mode.

Each model/effort pair is a separate test. Include `none,minimal` to probe those
levels as well; a model may reject them. Passing establishes request acceptance
and text completion, not comparative reasoning quality or identical behavior
across providers.

## Limitations

- The default backend is `https://chatgpt.com/backend-api/codex`. It is an
  experimental subscription transport and may change independently of the public
  OpenAI API.
- ChatGPT subscription billing and OpenAI API billing are separate. This module
  does not turn a subscription into API credit.
- Responses are requested as SSE but the current `ChatModel` aggregates the stream
  before returning. It does not expose token-by-token reactive streaming.
- The module provides the reusable core only. Auto-configuration and a starter are
  outside the first contribution.

## Tests

```bash
mvn -pl embabel-agent-codex test
```

The live smoke test is opt-in and uses the persistent Embabel store above. Override
its path with `EMBABEL_CODEX_AUTH_FILE` if needed; it never imports temporary CLI
credentials:

```bash
EMBABEL_LIVE_CODEX=1 EMBABEL_CODEX_MODEL=your-model-id \
  mvn -pl embabel-agent-codex -Dtest=CodexLiveIT test
```

To compare model availability, repeat this command sequentially with a different
`EMBABEL_CODEX_MODEL`. A successful live call reports one test with no failures,
errors, or skips. `BUILD SUCCESS` with a skipped test is not live verification.
The smoke test checks a fixed text response; it does not benchmark model quality.

## Prior art

- [Hermes Agent Codex OAuth](https://github.com/NousResearch/hermes-agent/blob/d604141d097eec4a49493ad1eaceb9b2ca1e496d/hermes_cli/auth.py#L86-L108)
  and [Responses adapter](https://github.com/NousResearch/hermes-agent/blob/d604141d097eec4a49493ad1eaceb9b2ca1e496d/agent/codex_responses_adapter.py#L823-L994)
- [OpenClaw Codex OAuth flow](https://github.com/openclaw/openclaw/blob/a65ad9a3fd1ce668c798ac2937eaac0626e4b6f4/docs/concepts/oauth.md#L129-L177)
  and [endpoint classification](https://github.com/openclaw/openclaw/blob/a65ad9a3fd1ce668c798ac2937eaac0626e4b6f4/extensions/openai/base-url.ts#L4-L20)
