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

## Prior art

- [Hermes Agent Codex OAuth](https://github.com/NousResearch/hermes-agent/blob/d604141d097eec4a49493ad1eaceb9b2ca1e496d/hermes_cli/auth.py#L86-L108)
  and [Responses adapter](https://github.com/NousResearch/hermes-agent/blob/d604141d097eec4a49493ad1eaceb9b2ca1e496d/agent/codex_responses_adapter.py#L823-L994)
- [OpenClaw Codex OAuth flow](https://github.com/openclaw/openclaw/blob/a65ad9a3fd1ce668c798ac2937eaac0626e4b6f4/docs/concepts/oauth.md#L129-L177)
  and [endpoint classification](https://github.com/openclaw/openclaw/blob/a65ad9a3fd1ce668c798ac2937eaac0626e4b6f4/extensions/openai/base-url.ts#L4-L20)
