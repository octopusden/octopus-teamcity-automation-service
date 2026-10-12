# octopus-teamcity-automation-service

REST service for automating TeamCity operations for octopus components.

It creates a component's TeamCity build chain, the same chain the `create-build-chain` command of
[octopus-teamcity-automation](https://github.com/octopusden/octopus-teamcity-automation) creates.
Both use one implementation, the `octopus-teamcity-automation-core` library; the service only adds
the HTTP contract, a check that nothing exists yet, and an account of what exists after a failure.

## API

`POST /rest/api/1/build-chains`

```json
{
  "teamcity": { "url": "https://teamcity.example", "user": "user", "password": "password" },
  "componentsRegistryUrl": "https://components-registry.example",
  "parentProjectId": "RDDepartment_Example",
  "component": "example-component",
  "minorVersion": "1.0",
  "createChecklist": true,
  "createRcForce": false
}
```

The service holds no credentials of its own: TeamCity is called as the user in the request, so
TeamCity decides what that user may do. The password is never logged or returned.

`201 Created` returns the ids of what was created; the RC and checklist ids are `null` when they were
not created:

```json
{
  "projectId": "RDDepartment_Example_ExampleComponent",
  "compileBuildTypeId": "RDDepartment_Example_ExampleComponent_10CompileUtAuto",
  "rcBuildTypeId": "RDDepartment_Example_ExampleComponent_20ReleaseCandidateManual",
  "checklistBuildTypeId": "RDDepartment_Example_ExampleComponent_30ReleaseChecklistValidationManual",
  "releaseBuildTypeId": "RDDepartment_Example_ExampleComponent_40ReleaseManual"
}
```

Errors carry `errorCode` and `errorMessage`:

| Status | `errorCode` | When |
|---|---|---|
| 400 | `VALIDATION` | A field is missing, blank, has surrounding whitespace, or a URL is not http(s) |
| 401 | `UNAUTHORIZED` | TeamCity rejected the credentials |
| 403 | `FORBIDDEN` | The TeamCity user lacks a permission |
| 404 | `NOT_FOUND` | The parent project, the component or its version does not exist |
| 409 | `ALREADY_EXISTS` | The component's project already exists under the parent; `projectId` names it. Nothing is changed: delete the project in TeamCity to create the chain again |
| 422 | `UNSUPPORTED_COMPONENT` | The build system or the VCS roots cannot be turned into a chain |
| 502 | `PARTIALLY_CREATED` | Creation failed after the project was created; `projectId` and `buildTypeIds` name what exists. Delete the project before trying again |
| 502 | `UPSTREAM_ERROR` | TeamCity or the Components Registry failed or is not reachable |

Everything before the project is created (credentials, parent, existing project, component,
build system, VCS roots) is checked first, so a 4xx never leaves anything in TeamCity.

The `client` module is a Feign client for this API that rethrows these errors as typed exceptions.

## Build

The library is published to GitHub Packages (`octopusden/octopus-maven-packages`) only, and GitHub
Packages authenticates every read. A build needs a token with `read:packages`:

```bash
export SHARED_PACKAGES_READ_USERNAME=$(gh api user -q .login) SHARED_PACKAGES_READ_TOKEN=$(gh auth token)
```

In CI, every workflow passes `resolve-from-shared-packages: true` to octopus-base, which then
exports the `OCTOPUS_SHARED_PACKAGES_READ_TOKEN` repository secret (a classic PAT with
`read:packages` only) under these names.

```bash
./gradlew build
```

## Functional tests

`ft` runs the service image against TeamCity 2022 and 2026 and the Components Registry in Docker:

```bash
./gradlew :ft:ft
```

Set `DOCKER_REGISTRY` to pull the TeamCity and registry images through a mirror.
