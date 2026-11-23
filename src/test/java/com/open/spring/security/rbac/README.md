# Who may make each request: the evidence

Spring decides who may make a request in three places that nobody reads together: two filter chains in
`SecurityConfig` and `MvcSecurityConfig` (first-match-wins lists of rules), annotations such as `@PreAuthorize` on
some handlers, and checks written inside the handlers themselves. This folder collects what is written down, so that a
policy (one file that says who may make each request) can later be checked against it.

This is evidence. Nothing here changes what the application does: it is test code, it never starts the application,
and it never calls a route.

## The checks

| Check | What it does | Why it exists | When to run | Takes |
|---|---|---|---|---|
| `ControllerInventoryTest` | Lists every request the controllers answer: method, path, which filter chain's matcher takes the path, the handler, the annotation guards on the method and the class, whether the controller exists only under a profile, and role words found in the class's source. Checks the listing on small controllers written to have known answers, and on this application: every method that carries a mapping annotation is listed, nothing else is, the scan is not empty, a test's own controllers are left out, and reading it twice gives the same listing. | A policy can only be as complete as the list of requests it is checked against. The method and path of each handler are worked out by Spring's own `RequestMappingHandlerMapping`, so this list is the one the running application uses. | After adding or moving a controller, after changing `ControllerInventory`, and before using the inventory to write or check a policy. | about 20 s, most of it the build |

```
./mvnw test -Dtest=ControllerInventoryTest
```

To also write the whole inventory (one record for each method and path) so a person can read it:

```
./mvnw test -Dtest=ControllerInventoryTest -Drbac.inventory.out=/a/folder/outside/the/repo/inventory.json
```

The file shows which requests carry no guard. **Write it outside the repository, and do not commit or post it.** The
test refuses a path inside the repository.

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
