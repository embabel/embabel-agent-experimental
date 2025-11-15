# Remote Action Support

Experimental support for remote actions, hosted on external servers.

This enables external servers to register actions that are eligible
for participation in planning and execution.

The contract is simple and easy to implement on any HTTP stack.

Remote servers must call the Embabel agent server to register themselves,
and expose a REST API to provide action and type metadata and
execute actions.

## Remote Server Endpoints

Remote servers must expose a REST API under `{baseUrl}/api/v1/` with the following endpoints:

### GET /api/v1/actions

Returns a list of available actions as JSON.

**Response:** Array of `RestActionMetadata` objects with structure:

```json
[
  {
    "name": "action_name",
    "description": "Action description",
    "inputs": [
      {
        "name": "input1",
        "type": "InputType"
      }
    ],
    "outputs": [
      {
        "name": "output1",
        "type": "OutputType"
      }
    ],
    "pre": [
      "thing1, thing2"
    ],
    "post": [
      "thing3"
    ],
    "cost": 0.5,
    "value": 0.8,
    "can_rerun": true
  }
]
```

### GET /api/v1/types

Returns a list of domain types known to the server as JSON.

**Response:** Array of `DynamicType` objects with structure:

```json
[
  {
    "name": "TypeName",
    "description": "Type description",
    "ownProperties": [
      {
        "name": "property1",
        "type": "string",
        "description": "Property description"
      }
    ],
    "parents": [],
    "creationPermitted": true
  }
]
```

### POST /api/v1/actions/execute

Executes an action with the given parameters.

**Request body:**

```json
{
  "action_name": "action_name",
  "parameters": {
    "input1": "value1",
    "input2": "value2"
  }
}
```

**Response:** JSON object matching the output type defined in the action metadata.

> Input and output types must be complex types, defined
> as `DynamicType` objects returned by the `/api/v1/types` endpoint.

**Example:**

```json
{
  "result": {
    ...
  },
  "status": "success"
}
```

## Registration

Remote servers must register via the Embabel server's REST API.

### POST /api/v1/remote/register server endpoint

Remote servers must invoke this endpoint to register with the Embabel server.

**Request body:**

```json
{
  "baseUrl": "http://localhost:8000",
  "name": "my-server",
  "description": "My remote action server"
}
```

**Response:** 200 OK on successful registration

This endpoint:

1. Creates a `RestServer` instance with the provided registration
2. Causes the Embabel server to invoke the the remote server to fetch actions and types
3. Deploys the remote actions into the agent platform

## Programmatic Registration

To register a remote server programmatically within an Embabel application (for example, to dynamically register a
server based on application logic),
create a `RestServerRegistration` object:

```kotlin
val registration = RestServerRegistration(
    baseUrl = "http://localhost:8000",
    name = "my-server",
    description = "My remote action server"
)
val restServer = RestServer(registration, restClient, objectMapper)
val agentScope = restServer.agentScope(agentPlatform)
agentPlatform.deploy(agentScope)
```

## GET /api/v1/remote server informational endpoint

Server informational endpoint to list all remote actions currently registered on the Embabel agent server.

**Response:** Array of `RestActionMetadata` objects for all registered remote actions

```json
[
  {
    "name": "remote_action_name",
    "description": "Remote action description",
    "inputs": [
      ...
    ],
    "outputs": [
      ...
    ],
    "pre": [
      ...
    ],
    "post": [
      ...
    ],
    "cost": 0.5,
    "value": 0.8,
    "can_rerun": true
  }
]
```

See `RestServerIT.kt` for integration test examples.

