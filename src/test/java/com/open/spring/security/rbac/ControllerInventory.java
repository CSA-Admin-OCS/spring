package com.open.spring.security.rbac;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every request this application's controllers answer, and what is written next to each one about who may make it.
 *
 * <p>This is evidence, not policy: it reads the code and says what is there. It never starts the application, never
 * calls a route and changes nothing. The method and path of each handler are worked out by Spring's own
 * {@link RequestMappingHandlerMapping}, so a class-level prefix, several paths, several methods and a mapping with no
 * method (which answers every method) come out the way the running application reads them.
 *
 * <p>What it cannot see is listed in README.md next to this file.
 */
public final class ControllerInventory {

    /** One request a controller answers: a method, a path pattern, and what is written beside the handler. */
    public record Handler(
            String method,            // GET, POST, ... or ANY when the mapping names no method
            String pattern,           // the path as Spring matches it, e.g. /api/person/{id}
            String chain,             // api, mvc or either: which filter chain's matcher takes this path
            String controller,        // the controller class
            String handler,           // the handler method, with its parameter types
            String declaredIn,        // the class that declares the handler method (differs when it is inherited)
            String kind,              // body: the return value is the response; view: it names a view
            List<String> guards,      // annotation guards on the method and on the class
            List<String> conditions,  // class-level conditions: the controller may not exist in every run
            List<String> rolesInCode) { // role words found in the class's source, outside comments and annotations

        public String request() {
            return method + " " + pattern;
        }
    }

    /** The package every controller of this application lives under. */
    public static final String BASE_PACKAGE = "com.open.spring";

    private static final List<Path> SOURCE_ROOTS = List.of(Path.of("src/main/java"), Path.of("src/test/java"));

    private static final List<String> GUARD_ANNOTATIONS = List.of(
            "org.springframework.security.access.prepost.PreAuthorize",
            "org.springframework.security.access.prepost.PostAuthorize",
            "org.springframework.security.access.annotation.Secured",
            "jakarta.annotation.security.RolesAllowed",
            "jakarta.annotation.security.PermitAll",
            "jakarta.annotation.security.DenyAll");

    private static final Pattern ROLE_WORD = Pattern.compile(
            "ROLE_[A-Z_]+|\\bhasRole\\(|\\bhasAnyRole\\(|\\bhasAuthority\\(|\\bhasAnyAuthority\\(|\\bgetAuthorities\\(|\\bgetRoles\\(|\\bisAdmin\\(|\\bisTeacher\\(");
    private static final Pattern GUARD_TEXT = Pattern.compile("@(?:PreAuthorize|PostAuthorize|Secured|RolesAllowed)\\s*\\([^\\n]*\\)");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    private ControllerInventory() {
    }

    /** Every request of this application's controllers, in a fixed order. */
    public static List<Handler> scan() throws IOException, ClassNotFoundException {
        return of(controllerClasses(BASE_PACKAGE).toArray(new Class<?>[0]));
    }

    /** The requests of these controller classes. Used on its own by the checks, on fixtures with known answers. */
    public static List<Handler> of(Class<?>... controllers) {
        Mappings mappings = new Mappings();
        List<Handler> out = new ArrayList<>();
        for (Class<?> controller : controllers) {
            Class<?> type = ClassUtils.getUserClass(controller);
            Map<Method, RequestMappingInfo> methods = MethodIntrospector.selectMethods(type,
                    (MethodIntrospector.MetadataLookup<RequestMappingInfo>) method -> mappings.infoFor(method, type));
            List<String> classGuards = guardsOf(type, "class");
            List<String> conditions = conditionsOf(type);
            List<String> rolesInCode = rolesInCode(type);
            boolean restController = AnnotatedElementUtils.hasAnnotation(type, RestController.class);
            boolean classBody = restController || AnnotatedElementUtils.hasAnnotation(type, ResponseBody.class);
            for (Map.Entry<Method, RequestMappingInfo> entry : methods.entrySet()) {
                Method method = entry.getKey();
                RequestMappingInfo info = entry.getValue();
                List<String> guards = new ArrayList<>(guardsOf(method, "method"));
                guards.addAll(classGuards);
                String kind = classBody || AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class) ? "body" : "view";
                for (String pattern : patternsOf(info)) {
                    for (String httpMethod : methodsOf(info)) {
                        out.add(new Handler(httpMethod, pattern, chainOf(pattern), type.getName(), signature(method),
                                method.getDeclaringClass().getName(), kind, guards, conditions, rolesInCode));
                    }
                }
            }
        }
        out.sort(Comparator.comparing(Handler::pattern).thenComparing(Handler::method).thenComparing(Handler::controller).thenComparing(Handler::handler));
        return out;
    }

    /** The controller classes a running application would register: concrete, top-level or static, and not a test class. */
    public static List<Class<?>> controllerClasses(String basePackage) throws IOException, ClassNotFoundException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        ClassLoader loader = ControllerInventory.class.getClassLoader();
        List<Class<?>> found = new ArrayList<>();
        for (Resource resource : resolver.getResources("classpath*:" + basePackage.replace('.', '/') + "/**/*.class")) {
            if (resource.getURL().toString().contains("/test-classes/")) {
                continue;  // a test's own controllers are not part of the application
            }
            AnnotationMetadata metadata = readers.getMetadataReader(resource).getAnnotationMetadata();
            if (!metadata.isConcrete() || !metadata.isIndependent()) {
                continue;
            }
            boolean mapped = metadata.isAnnotated(Controller.class.getName()) || metadata.isAnnotated(RequestMapping.class.getName());
            if (!mapped) {
                continue;
            }
            // not initialised: loading a class to read its annotations must not run anything in it
            found.add(ClassUtils.forName(metadata.getClassName(), loader));
        }
        found.sort(Comparator.comparing(Class::getName));
        return found;
    }

    /** Counts that say what the inventory holds, for a person to read. */
    public static Map<String, Object> summary(List<Handler> handlers) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("controllers", handlers.stream().map(Handler::controller).distinct().count());
        out.put("requests", handlers.size());
        out.put("byMethod", count(handlers, Handler::method));
        out.put("byChain", count(handlers, Handler::chain));
        out.put("byKind", count(handlers, Handler::kind));
        out.put("withGuardAnnotation", handlers.stream().filter(h -> !h.guards().isEmpty()).count());
        out.put("withRoleWordsInSource", handlers.stream().filter(h -> !h.rolesInCode().isEmpty()).count());
        out.put("inControllersWithConditions", handlers.stream().filter(h -> !h.conditions().isEmpty()).count());
        return out;
    }

    private static Map<String, Long> count(List<Handler> handlers, java.util.function.Function<Handler, String> key) {
        return handlers.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
    }

    // ---------------------------------------------------------------- reading one handler

    /** Spring's own reading of a handler method's mapping: the same code the running application uses. */
    private static final class Mappings extends RequestMappingHandlerMapping {
        Mappings() {
            GenericApplicationContext context = new GenericApplicationContext();
            context.refresh();
            setApplicationContext(context);
            afterPropertiesSet();
        }

        RequestMappingInfo infoFor(Method method, Class<?> type) {
            return getMappingForMethod(method, type);
        }
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        Set<String> patterns = new TreeSet<>();
        if (info.getPathPatternsCondition() != null) {
            patterns.addAll(info.getPathPatternsCondition().getPatternValues());
        } else if (info.getPatternsCondition() != null) {
            patterns.addAll(info.getPatternsCondition().getPatterns());
        }
        Set<String> out = new TreeSet<>();
        for (String pattern : patterns) {
            if (pattern.contains("${")) {
                throw new IllegalStateException("a mapping with a placeholder is not resolved here: " + pattern);
            }
            out.add(pattern.isEmpty() ? "/" : pattern);
        }
        return out.isEmpty() ? Set.of("/") : out;
    }

    private static Set<String> methodsOf(RequestMappingInfo info) {
        Set<String> out = new TreeSet<>();
        for (RequestMethod method : info.getMethodsCondition().getMethods()) {
            out.add(method.name());
        }
        return out.isEmpty() ? Set.of("ANY") : out;
    }

    /** Which filter chain's matcher takes the path: SecurityConfig's API chain takes /api and /authenticate, the MVC chain the rest. */
    static String chainOf(String pattern) {
        if (pattern.equals("/authenticate") || pattern.equals("/api") || pattern.startsWith("/api/")) {
            return "api";
        }
        if (pattern.startsWith("/{") || pattern.startsWith("/*")) {
            return "either";  // a first segment that is a variable could be /api
        }
        return "mvc";
    }

    private static String signature(Method method) {
        return method.getName() + "(" + java.util.Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).collect(Collectors.joining(",")) + ")";
    }

    static String signatureOf(Method method) {
        return signature(method);
    }

    private static List<String> guardsOf(AnnotatedElement element, String where) {
        List<String> out = new ArrayList<>();
        for (String name : GUARD_ANNOTATIONS) {
            AnnotationAttributes attributes = AnnotatedElementUtils.findMergedAnnotationAttributes(element, name, false, false);
            if (attributes != null) {
                String simple = name.substring(name.lastIndexOf('.') + 1);
                out.add(where + " @" + simple + "(" + valueText(attributes.get("value")) + ")");
            }
        }
        return out;
    }

    private static List<String> conditionsOf(Class<?> type) {
        List<String> out = new ArrayList<>();
        for (Annotation annotation : type.getAnnotations()) {
            String name = annotation.annotationType().getName();
            boolean condition = name.startsWith("org.springframework.boot.autoconfigure.condition.Conditional")
                    || name.equals("org.springframework.context.annotation.Profile")
                    || name.equals("org.springframework.context.annotation.Conditional");
            if (condition) {
                out.add("@" + annotation.annotationType().getSimpleName() + "(" + valueText(AnnotationUtils.getValue(annotation)) + ")");
            }
        }
        return out;
    }

    private static String valueText(Object value) {
        if (value == null) {
            return "";
        }
        if (value.getClass().isArray()) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                parts.add(String.valueOf(Array.get(value, i)));
            }
            return String.join(",", parts);
        }
        return String.valueOf(value);
    }

    /**
     * Role words in the class's source file, outside comments and outside annotations: a hint that the class decides
     * who may do something by itself, which no annotation and no filter-chain rule shows. Approximate: it reads text,
     * and it cannot say which method the check is in. A person reads the class.
     */
    static List<String> rolesInCode(Class<?> type) {
        String top = type.getName().split("\\$")[0];
        String relative = top.replace('.', '/') + ".java";
        for (Path root : SOURCE_ROOTS) {
            Path file = root.resolve(relative);
            if (Files.isRegularFile(file)) {
                try {
                    return roleWords(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    return List.of("(source could not be read)");
                }
            }
        }
        return List.of("(source not found)");
    }

    static List<String> roleWords(String source) {
        String text = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        text = LINE_COMMENT.matcher(text).replaceAll(" ");
        text = GUARD_TEXT.matcher(text).replaceAll(" ");
        Set<String> words = new TreeSet<>();
        Matcher matcher = ROLE_WORD.matcher(text);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return new ArrayList<>(words);
    }
}
