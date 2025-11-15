# Remote Action Support

**Experimental** support for remote actions.

This enables external servers to register actions that are eligible
for participation in planning and execution.

The contract is simple and easy to implement on any HTTP stack.

## Server Contract

Client servers must expose a REST API under `{baseUrl}/api/v1/` with the following endpoints:

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

To register a remote server, create a `RestServerRegistration`:

```kotlin
val registration = RestServerRegistration(
    baseUrl = "http://localhost:8000",
    name = "my-server",
    description = "My remote action server"
)
```

See `RestServerIT.kt` for integration test examples.