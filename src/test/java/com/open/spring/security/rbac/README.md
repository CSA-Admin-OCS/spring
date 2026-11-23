# Who may make each request: the evidence

Spring decides who may make a request in three places that nobody reads together: two filter chains in
`SecurityConfig` and `MvcSecurityConfig` (first-match-wins lists of rules), annotations such as `@PreAuthorize` on
some handlers, and checks written inside the handlers themselves. This folder collects what is written down, so that a
policy (one file that says who may make each request) can later be checked against it.

This is evidence. Nothing here changes what the application does: it is test code, it never starts the application,
and it never calls a controller.

## The checks

| Check | What it does | Why it exists | When to run | Takes |
|---|---|---|---|---|
| `ControllerInventoryTest` | Lists every request the controllers answer: method, path, which filter chain's matcher takes the path, the handler, the annotation guards on the method and the class, whether the controller exists only under a profile, and role words found in the class's source. Checks the listing on small controllers written to have known answers, and on this application: every method that carries a mapping annotation is listed, nothing else is, the scan is not empty, a test's own controllers are left out, and reading it twice gives the same listing. | A policy can only be as complete as the list of requests it is checked against. The method and path of each handler are worked out by Spring's own `RequestMappingHandlerMapping`, so this list is the one the running application uses. | After adding or moving a controller, after changing `ControllerInventory`, and before using the inventory to write or check a policy. | about 20 s, most of it the build |
| `ProbeFixtureTest` | Checks the prober on two small filter chains written to have known answers: an open rule, a signed-in rule, a list of roles, one role, the default rule, a page chain that sends a caller with no login to the login page, and that a bad or expired token is treated as no login. | The prober is a tool, and a tool checked only against the application proves nothing: here the right answer is known. | After changing `ProbeContext` or `Probe`. | about 5 s |
| `ReadingTest` | Checks how answers and annotations are read into one vocabulary (`anyone`, `signed_in`, `roles:...`, `nobody`): a single guard call, calls joined by `or` and `and`, a guard on the method over one on the class, `@Secured` and `@RolesAllowed`, and a guard too complex to read, which is listed for a person and not guessed at. Also each kind of finding. | The comparison between the chain, the annotations and the code is only as good as these rules. | After changing `Reading`. | about 2 s |
| `RealChainsProbeTest` | Builds this application's real `SecurityConfig` and `MvcSecurityConfig` in a small context and asks them about every request in the inventory as ten kinds of caller. Checks that no real controller was reached, that every answer is one a chain gives, that the inventory's chain for each path is the chain that takes it, that a bad token is always treated as no login, and that the reading agrees with every route in `SampleLabels`. | This is where the filter chains' rules are read as they run, not as they are written: the rules are first-match-wins, so two lines that look the same can answer differently. `SampleLabels` holds about 80 routes worked out by hand from the rules, so the tool is checked against a person, not against itself. | After changing a rule in either security config, a controller path, or the probe; and before using the report to write or check a policy. | about 15 s, most of it the build |

```
./mvnw test -Dtest=ControllerInventoryTest
```

To also write the whole inventory (one record for each method and path) so a person can read it:

```
./mvnw test -Dtest=ControllerInventoryTest -Drbac.inventory.out=/a/folder/outside/the/repo/inventory.json
```

The file shows which requests carry no guard. **Write it outside the repository, and do not commit or post it.** The
test refuses a path inside the repository.

## The probe

`RealChainsProbeTest` asks the real filter chains about every request as ten kinds of caller: no login, a bad token,
an expired token, a signed-in account with no role, and one account for each role the application uses (`ROLE_USER`,
`ROLE_STUDENT`, `ROLE_MENTOR`, `ROLE_TEACHER`, `ROLE_ADMIN`, `ROLE_ASSIGNMENT_SYNC`). The API chain reads the token cookie
and the MVC chain reads the session, so a signed-in caller is sent both, as a signed-in browser is. A request that
names no method is asked with each of GET, POST, PUT, DELETE and PATCH.

What each caller got is one of `reached` (the request got through to a controller), `401` or `403`, or
`redirect:/login`. From the answers the probe reads a level: `anyone`, `signed_in` (any signed-in account, even one
with no role), `roles:ROLE_A,ROLE_B`, or `nobody`. It then compares that with the annotation guards and with the role
words in each class, and lists where they differ: a request open at the chain with nothing else guarding it, one open
at the chain and guarded by an annotation or by code, an annotation that allows who the chain refuses (so it never
gets the chance), a chain that lets through who the annotation refuses (so the annotation decides), a route no kind of
caller can reach, and a guard too complex to read.

To also write the full report (every request, what each caller got, its level, its handlers and the findings):

```
./mvnw test -Dtest=RealChainsProbeTest -Drbac.probe.out=/a/folder/outside/the/repo/probe.json
```

**Write it outside the repository, and do not commit or post it.** It shows which requests anyone can make.

### What the probe is, and is not

- It asks the filter chains only. The one controller in the test context is a stand-in that answers "reached" to every
  path and method, so `@PreAuthorize` and the checks inside handlers are **not** run. They are read from source and
  shown next to the chain's answer.
- Two things differ from the running application, on purpose. The rate limiter is switched off, because it decides how
  many requests a caller may make, not who may make one. People are not loaded from a database: a token's subject
  carries the roles the caller is to have.
- Each caller holds one role. An account that holds two roles is not asked.
- The chain's `/error` handling, CORS preflight (`OPTIONS`) and the WebSocket endpoints are not asked.

## What the inventory cannot see

It lists requests that controller methods answer. It does not list, and a person has to look at:

- **WebSocket and STOMP endpoints:** the three WebSocket configurations (`mortevision/archive`,
  `mortevision/nativesocket`, `groups/WebSocketBrokerConfig`) and the `@MessageMapping` handlers in
  `GroupChatWebSocketController`. They are not `@RequestMapping` handlers.
- **Static resources** added in `MvcConfig`, Spring Boot's own `/error` page, and anything registered in code instead
  of by annotation.
- **What a handler decides by itself.** `rolesInCode` is only a word list: the role words that appear in the class's
  source, outside comments and annotations. It cannot say which method the check is in or what it does. Read the class.
- **Whether an annotation guard works.** `@PreAuthorize` and the like are shown as text. They are not evaluated.

A controller that exists only under a profile or a property is listed with its condition; none is conditional today.
