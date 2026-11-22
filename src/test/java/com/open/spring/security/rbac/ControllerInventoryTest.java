package com.open.spring.security.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.security.rbac.ControllerInventory.Handler;
import com.open.spring.security.rbac.fixtures.ApiFixture;
import com.open.spring.security.rbac.fixtures.ConditionalFixture;
import com.open.spring.security.rbac.fixtures.RoleCheckFixture;
import com.open.spring.security.rbac.fixtures.ViewFixture;

/**
 * Checks the controller inventory: first on small controllers written to have known answers, then on this application.
 *
 * <p>Run:  ./mvnw test -Dtest=ControllerInventoryTest
 * <p>To also write the full inventory (one record per method and path) outside the repository:
 * ./mvnw test -Dtest=ControllerInventoryTest -Drbac.inventory.out=/some/folder/outside/the/repo/inventory.json
 * The file shows which requests carry no guard, so it is never committed or posted.
 */
class ControllerInventoryTest {

    private static List<Handler> of(Class<?> controller) {
        return ControllerInventory.of(controller);
    }

    private static Set<String> requests(List<Handler> handlers) {
        return handlers.stream().map(Handler::request).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Handler only(List<Handler> handlers, String request) {
        List<Handler> found = handlers.stream().filter(h -> h.request().equals(request)).toList();
        assertEquals(1, found.size(), request + " should be listed once, found " + found.size());
        return found.get(0);
    }

    // ---------------------------------------------------------------- on controllers with known answers

    @Test
    void aClassPrefixIsJoinedToEachMethodPath() {
        assertEquals(Set.of("DELETE /api/a/two", "GET /api/a/two", "GET /api/a/x", "POST /api/a/p", "POST /api/a/q",
                "ANY /api/a/any", "DELETE /api/a/u/{id}"), requests(of(ApiFixture.class)));
    }

    @Test
    void aMethodThatIsNotAHandlerIsNotListed() {
        assertTrue(of(ApiFixture.class).stream().noneMatch(h -> h.handler().startsWith("notAHandler")));
    }

    @Test
    void severalPathsAndSeveralMethodsEachBecomeTheirOwnRequest() {
        List<Handler> handlers = of(ApiFixture.class);
        assertEquals(2, handlers.stream().filter(h -> h.handler().equals("twoPaths()")).count());
        assertEquals(2, handlers.stream().filter(h -> h.handler().equals("twoMethods()")).count());
    }

    @Test
    void aMappingThatNamesNoMethodAnswersEveryMethod() {
        assertEquals("noMethodNamed()", only(of(ApiFixture.class), "ANY /api/a/any").handler());
    }

    @Test
    void guardsOnTheMethodAndOnTheClassAreBothShown() {
        List<Handler> handlers = of(ApiFixture.class);
        assertEquals(List.of("method @PreAuthorize(hasRole('ADMIN'))", "class @PreAuthorize(isAuthenticated())"),
                only(handlers, "DELETE /api/a/u/{id}").guards());
        assertEquals(List.of("class @PreAuthorize(isAuthenticated())"), only(handlers, "GET /api/a/x").guards());
    }

    @Test
    void theChainIsTheOneWhoseMatcherTakesThePath() {
        List<Handler> handlers = of(ViewFixture.class);
        assertEquals("api", only(handlers, "POST /authenticate").chain());
        assertEquals("mvc", only(handlers, "GET /mvc/page").chain());
        assertEquals("either", only(handlers, "GET /{anything}/x").chain());
        assertEquals("api", only(of(ApiFixture.class), "GET /api/a/x").chain());
    }

    @Test
    void aPageControllerAnswersWithAViewUnlessTheMethodReturnsABody() {
        List<Handler> handlers = of(ViewFixture.class);
        assertEquals("view", only(handlers, "GET /mvc/page").kind());
        assertEquals("body", only(handlers, "GET /mvc/data").kind());
        assertEquals("body", only(of(ApiFixture.class), "GET /api/a/x").kind());
    }

    @Test
    void aControllerThatExistsOnlyUnderAProfileSaysSo() {
        assertEquals(List.of("@Profile(never-active)"), only(of(ConditionalFixture.class), "GET /api/cond").conditions());
        assertTrue(only(of(ApiFixture.class), "GET /api/a/x").conditions().isEmpty());
    }

    @Test
    void roleWordsInCodeAreFoundButNotThoseInCommentsOrAnnotations() {
        List<Handler> handlers = of(RoleCheckFixture.class);
        assertEquals(List.of("ROLE_ADMIN", "hasRole("), only(handlers, "GET /api/rc/check").rolesInCode());
        assertEquals(List.of("ROLE_ADMIN", "hasRole("), only(handlers, "GET /api/rc/annotated").rolesInCode());
        assertTrue(only(of(ApiFixture.class), "GET /api/a/x").rolesInCode().isEmpty());
    }

    @Test
    void roleWordsAreReadFromTextWithCommentsAndAnnotationsTakenOut() {
        String source = """
                // ROLE_LINE hasRole(
                /* ROLE_BLOCK
                   hasAuthority( */
                @PreAuthorize("hasRole('ROLE_ANNOTATION')")
                void m() { if (user.hasAnyRole("ROLE_REAL")) { } user.isAdmin(); }
                """;
        assertEquals(List.of("ROLE_REAL", "hasAnyRole(", "isAdmin("), ControllerInventory.roleWords(source));
    }

    @Test
    void aSourceFileThatCannotBeFoundIsSaidSo() {
        assertEquals(List.of("(source not found)"), ControllerInventory.rolesInCode(String.class));
    }

    // ---------------------------------------------------------------- on this application

    private static boolean mapped(Method method) {
        return !method.isBridge() && !method.isSynthetic() && AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class);
    }

    @Test
    void everyMethodTheControllersMapIsInTheInventory() throws Exception {
        List<Class<?>> controllers = ControllerInventory.controllerClasses(ControllerInventory.BASE_PACKAGE);
        List<Handler> inventory = ControllerInventory.of(controllers.toArray(new Class<?>[0]));

        // the second count does not use Spring's mapping code: it asks each method whether a mapping annotation is on it
        Set<String> mappedByAnnotation = new TreeSet<>();
        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                if (mapped(method)) {
                    mappedByAnnotation.add(controller.getName() + "#" + ControllerInventory.signatureOf(method));
                }
            }
        }
        Set<String> listed = new TreeSet<>();
        for (Handler handler : inventory) {
            if (handler.declaredIn().equals(handler.controller())) {
                listed.add(handler.controller() + "#" + handler.handler());
            }
        }

        Set<String> missing = new TreeSet<>(mappedByAnnotation);
        missing.removeAll(listed);
        Set<String> extra = new TreeSet<>(listed);
        extra.removeAll(mappedByAnnotation);
        assertTrue(missing.isEmpty(), "mapped by annotation but not in the inventory: " + missing);
        assertTrue(extra.isEmpty(), "in the inventory but not mapped by annotation: " + extra);
    }

    @Test
    void theScanIsNotSilentlyEmpty() throws Exception {
        List<Class<?>> controllers = ControllerInventory.controllerClasses(ControllerInventory.BASE_PACKAGE);
        List<Handler> inventory = ControllerInventory.of(controllers.toArray(new Class<?>[0]));
        // a floor, not a count to keep up to date: it only catches a scan that found (nearly) nothing
        assertTrue(controllers.size() >= 100, "found only " + controllers.size() + " controller classes");
        assertTrue(inventory.size() >= 600, "found only " + inventory.size() + " requests");
    }

    @Test
    void aTestsOwnControllersAreNotPartOfTheApplication() throws Exception {
        Set<String> names = ControllerInventory.controllerClasses(ControllerInventory.BASE_PACKAGE).stream()
                .map(Class::getName).collect(Collectors.toSet());
        assertTrue(names.stream().noneMatch(name -> name.contains(".rbac.fixtures.")), "fixtures were scanned: " + names);
    }

    @Test
    void everyRequestHasAMethodAndAPath() throws Exception {
        for (Handler handler : ControllerInventory.scan()) {
            assertFalse(handler.method().isBlank(), handler.toString());
            assertTrue(handler.pattern().startsWith("/"), "a pattern should start with /: " + handler);
            assertTrue(Set.of("api", "mvc", "either").contains(handler.chain()), handler.toString());
        }
    }

    @Test
    void readingItTwiceGivesTheSameInventory() throws Exception {
        assertEquals(ControllerInventory.scan(), ControllerInventory.scan());
    }

    @Test
    void theInventoryIsSummarisedAndMayBeWrittenOutsideTheRepository() throws Exception {
        List<Handler> inventory = ControllerInventory.scan();
        Map<String, Object> summary = ControllerInventory.summary(inventory);
        System.out.println("RBAC INVENTORY " + summary);
        String out = System.getProperty("rbac.inventory.out");
        if (out != null && !out.isBlank()) {
            Path file = Path.of(out).toAbsolutePath();
            Path repo = Path.of("").toAbsolutePath();
            assertFalse(file.startsWith(repo), "write the inventory outside the repository, not to " + file);
            Files.createDirectories(file.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), inventory);
            System.out.println("RBAC INVENTORY written to " + file);
        }
        assertEquals(inventory.size(), summary.get("requests"));
    }
}
