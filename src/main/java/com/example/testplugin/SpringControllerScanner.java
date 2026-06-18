package com.example.testplugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Scans a PsiClass for Spring MVC controller endpoints, extracts their
 * metadata (URL, HTTP method, parameters, DTO fields, return type), and
 * serialises the result to JSON.
 *
 * <p>Usage:
 * <pre>
 *   SpringControllerScanner.scan(project, psiClass, json -> syncToDocSystem(json));
 * </pre>
 *
 * <p>Everything runs inside a {@link Task.Backgroundable} so the UI thread
 * is never blocked.
 */
public final class SpringControllerScanner {

    // ── Spring mapping annotation FQNs ──────────────────────────────────────

    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping"
    );

    // ── Parameter annotation FQNs ────────────────────────────────────────────

    private static final String ANN_REQUEST_BODY  = "org.springframework.web.bind.annotation.RequestBody";
    private static final String ANN_REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam";
    private static final String ANN_PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SpringControllerScanner() {
    }

    // ── Public entry point ───────────────────────────────────────────────────

    /**
     * Starts a background task that scans {@code targetClass} and calls
     * {@code onComplete} with the resulting JSON string on the EDT.
     *
     * @param project     current IDEA project
     * @param targetClass the controller PsiClass to scan
     * @param onComplete  callback receiving the JSON result (runs on EDT)
     */
    public static void scan(
            @NotNull Project project,
            @NotNull PsiClass targetClass,
            @NotNull Consumer<String> onComplete
    ) {
        ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "Scanning Spring Controller", false) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        indicator.setIndeterminate(true);
                        indicator.setText("Extracting endpoint metadata…");

                        // All PSI reads must happen inside a ReadAction
                        String json = ReadAction.compute(() -> doScan(targetClass));

                        // Deliver result on UI thread
                        com.intellij.openapi.application.ApplicationManager
                                .getApplication()
                                .invokeLater(() -> onComplete.accept(json));
                    }
                }
        );
    }

    // ── Core scanning logic (must run inside ReadAction) ────────────────────

    /**
     * Walks the class with a {@link JavaRecursiveElementVisitor}, collects
     * every method annotated with a Spring mapping annotation, and serialises
     * the aggregated data to JSON.
     */
    private static @NotNull String doScan(@NotNull PsiClass targetClass) {
        // Class-level base path from @RequestMapping on the class itself
        String classPath = extractPath(targetClass.getAnnotation(
                "org.springframework.web.bind.annotation.RequestMapping"));

        List<Map<String, Object>> endpoints = new ArrayList<>();

        // PsiRecursiveElementVisitor traverses the entire PSI tree depth-first;
        // we override visitMethod to intercept every method declaration.
        targetClass.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method); // keep recursing into nested classes

                PsiAnnotation mappingAnn = findMappingAnnotation(method);
                if (mappingAnn == null) {
                    return; // not a mapped endpoint
                }

                Map<String, Object> endpoint = new LinkedHashMap<>();
                endpoint.put("methodName", method.getName());
                endpoint.put("httpMethod", resolveHttpMethod(mappingAnn));
                endpoint.put("url", joinPaths(classPath, extractPath(mappingAnn)));
                endpoint.put("parameters", extractParameters(method));
                endpoint.put("returnType", extractReturnType(method));
                endpoint.put("javadoc", extractJavadoc(method));
                // Method body source code – gives the LLM visibility into business logic
                endpoint.put("methodBody", extractMethodBody(method));

                endpoints.add(endpoint);
            }
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("controller", targetClass.getQualifiedName());
        result.put("endpoints", endpoints);

        return GSON.toJson(result);
    }

    // ── Annotation helpers ───────────────────────────────────────────────────

    /**
     * Returns the first Spring mapping annotation found on {@code method},
     * or {@code null} if the method is not a mapped endpoint.
     */
    private static @Nullable PsiAnnotation findMappingAnnotation(@NotNull PsiMethod method) {
        for (PsiAnnotation ann : method.getAnnotations()) {
            String fqn = ann.getQualifiedName();
            if (fqn != null && MAPPING_ANNOTATIONS.contains(fqn)) {
                return ann;
            }
        }
        return null;
    }

    /**
     * Derives the HTTP method name from the annotation type.
     * {@code @RequestMapping} can carry an explicit {@code method} attribute.
     */
    private static @NotNull String resolveHttpMethod(@NotNull PsiAnnotation ann) {
        String fqn = ann.getQualifiedName();
        if (fqn == null) return "UNKNOWN";
        return switch (fqn) {
            case "org.springframework.web.bind.annotation.GetMapping"    -> "GET";
            case "org.springframework.web.bind.annotation.PostMapping"   -> "POST";
            case "org.springframework.web.bind.annotation.PutMapping"    -> "PUT";
            case "org.springframework.web.bind.annotation.DeleteMapping" -> "DELETE";
            case "org.springframework.web.bind.annotation.PatchMapping"  -> "PATCH";
            default -> {
                // @RequestMapping – inspect the method() attribute
                PsiAnnotationMemberValue methodAttr = ann.findAttributeValue("method");
                if (methodAttr != null) {
                    yield resolveEnumText(methodAttr);
                }
                yield "GET"; // Spring default
            }
        };
    }

    /**
     * Extracts the {@code value} / {@code path} attribute of a mapping
     * annotation as a single path string.  Returns {@code ""} when absent.
     */
    private static @NotNull String extractPath(@Nullable PsiAnnotation ann) {
        if (ann == null) return "";
        // Try "value" first, then "path"
        for (String attr : new String[]{"value", "path"}) {
            PsiAnnotationMemberValue val = ann.findAttributeValue(attr);
            if (val instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
                return s;
            }
            // Array initialiser – take first element
            if (val instanceof PsiArrayInitializerMemberValue arr) {
                PsiAnnotationMemberValue[] initializers = arr.getInitializers();
                if (initializers.length > 0
                        && initializers[0] instanceof PsiLiteralExpression lit
                        && lit.getValue() instanceof String s) {
                    return s;
                }
            }
        }
        return "";
    }

    /** Concatenates a class-level base path with a method-level path. */
    private static @NotNull String joinPaths(@NotNull String base, @NotNull String method) {
        if (base.isEmpty()) return method.isEmpty() ? "/" : method;
        if (method.isEmpty()) return base;
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String m = method.startsWith("/") ? method : "/" + method;
        return b + m;
    }

    /**
     * Resolves the text of an enum-like attribute value
     * (e.g. {@code RequestMethod.POST}).
     */
    private static @NotNull String resolveEnumText(@NotNull PsiAnnotationMemberValue value) {
        if (value instanceof PsiReferenceExpression ref) {
            return ref.getReferenceName() != null ? ref.getReferenceName() : value.getText();
        }
        if (value instanceof PsiArrayInitializerMemberValue arr) {
            PsiAnnotationMemberValue[] inits = arr.getInitializers();
            if (inits.length > 0) return resolveEnumText(inits[0]);
        }
        return value.getText();
    }

    // ── Parameter extraction ─────────────────────────────────────────────────

    /**
     * Iterates over all parameters of {@code method}, detects binding
     * annotations, and recursively expands DTO types.
     *
     * @return a list of parameter descriptor maps
     */
    private static @NotNull List<Map<String, Object>> extractParameters(@NotNull PsiMethod method) {
        List<Map<String, Object>> params = new ArrayList<>();

        for (PsiParameter param : method.getParameterList().getParameters()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", param.getName());
            p.put("type", param.getType().getPresentableText());

            // Detect binding annotation
            String binding = detectBindingAnnotation(param);
            p.put("binding", binding);

            // For @RequestBody, expand the DTO fields recursively
            if ("RequestBody".equals(binding)) {
                p.put("fields", extractDtoFields(param.getType(), 0));
            }

            params.add(p);
        }
        return params;
    }

    /**
     * Returns a short annotation label ({@code "RequestBody"},
     * {@code "RequestParam"}, {@code "PathVariable"}, or {@code "none"}).
     */
    private static @NotNull String detectBindingAnnotation(@NotNull PsiParameter param) {
        for (PsiAnnotation ann : param.getAnnotations()) {
            String fqn = ann.getQualifiedName();
            if (ANN_REQUEST_BODY.equals(fqn))  return "RequestBody";
            if (ANN_REQUEST_PARAM.equals(fqn)) return "RequestParam";
            if (ANN_PATH_VARIABLE.equals(fqn)) return "PathVariable";
        }
        return "none";
    }

    // ── DTO field extraction ─────────────────────────────────────────────────

    private static final int MAX_RECURSION_DEPTH = 3; // guard against circular references

    /**
     * Recursively resolves a {@link PsiType} to its {@link PsiClass} and
     * collects field name, type, and Javadoc for each declared field.
     *
     * @param type  the PSI type to expand
     * @param depth current recursion depth (stops at {@link #MAX_RECURSION_DEPTH})
     * @return list of field descriptor maps, or empty list for primitives/JDK types
     */
    private static @NotNull List<Map<String, Object>> extractDtoFields(
            @NotNull PsiType type, int depth
    ) {
        if (depth >= MAX_RECURSION_DEPTH) return List.of();

        // Resolve to PsiClass; PsiClassType.resolve() returns null for generics/arrays
        if (!(type instanceof PsiClassType classType)) return List.of();
        PsiClass resolved = classType.resolve();
        if (resolved == null) return List.of();

        String qualifiedName = resolved.getQualifiedName();
        // Skip JDK built-in classes (java.*, javax.*) – not user-defined DTOs
        if (qualifiedName == null
                || qualifiedName.startsWith("java.")
                || qualifiedName.startsWith("javax.")
                || qualifiedName.startsWith("kotlin.")) {
            return List.of();
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        // getAllFields() includes inherited fields; use getFields() for declared only
        for (PsiField field : resolved.getFields()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", field.getName());
            f.put("type", field.getType().getPresentableText());
            f.put("javadoc", extractJavadoc(field));

            // Recursively expand nested DTO types
            List<Map<String, Object>> nested = extractDtoFields(field.getType(), depth + 1);
            if (!nested.isEmpty()) {
                f.put("fields", nested);
            }

            fields.add(f);
        }
        return fields;
    }

    // ── Return type extraction ───────────────────────────────────────────────

    /**
     * Extracts the return type of {@code method} and, if it resolves to a
     * user-defined class, also returns its DTO field structure.
     */
    private static @NotNull Map<String, Object> extractReturnType(@NotNull PsiMethod method) {
        Map<String, Object> ret = new LinkedHashMap<>();
        PsiType returnType = method.getReturnType();
        if (returnType == null) {
            ret.put("type", "void");
            return ret;
        }

        ret.put("type", returnType.getPresentableText());

        // Unwrap ResponseEntity<T> or similar single-generic wrappers
        PsiType unwrapped = unwrapGeneric(returnType);
        List<Map<String, Object>> fields = extractDtoFields(unwrapped, 0);
        if (!fields.isEmpty()) {
            ret.put("fields", fields);
        }
        return ret;
    }

    /**
     * If {@code type} is a generic class with exactly one type argument
     * (e.g. {@code ResponseEntity<Foo>}), returns that argument; otherwise
     * returns {@code type} unchanged.
     */
    private static @NotNull PsiType unwrapGeneric(@NotNull PsiType type) {
        if (type instanceof PsiClassType classType) {
            PsiType[] args = classType.getParameters();
            if (args.length == 1) return args[0];
        }
        return type;
    }

    // ── Javadoc extraction ───────────────────────────────────────────────────

    /**
     * Returns the Javadoc comment text for a {@link PsiDocCommentOwner}
     * (method or field).  Returns {@code ""} when no Javadoc is present.
     */
    private static @NotNull String extractJavadoc(@NotNull PsiElement element) {
        if (!(element instanceof PsiDocCommentOwner owner)) return "";
        PsiDocComment doc = owner.getDocComment();
        if (doc == null) return "";
        // Strip the /** … */ delimiters and leading asterisks for readability
        StringBuilder sb = new StringBuilder();
        Arrays.stream(doc.getDescriptionElements())
              .map(PsiElement::getText)
              .map(String::trim)
              .filter(t -> !t.isEmpty())
              .forEach(t -> sb.append(t).append(" "));
        return sb.toString().trim();
    }

    // ── Method body extraction ───────────────────────────────────────────────

    /**
     * Returns the raw source text of the method body (the {@code { … }} block).
     * The LLM uses this to infer business logic, error handling, and side effects.
     * Returns {@code ""} for abstract / interface methods that have no body.
     */
    private static @NotNull String extractMethodBody(@NotNull PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        return body != null ? body.getText() : "";
    }

    // ── AI / Doc system placeholder ──────────────────────────────────────────

    /**
     * Placeholder for sending the extracted JSON to an AI or documentation API.
     *
     * <p>Replace this stub with the actual HTTP call, e.g.:
     * <pre>
     *   HttpClient client = HttpClient.newHttpClient();
     *   HttpRequest request = HttpRequest.newBuilder()
     *       .uri(URI.create("https://your-ai-api/endpoint"))
     *       .header("Content-Type", "application/json")
     *       .POST(HttpRequest.BodyPublishers.ofString(jsonData))
     *       .build();
     *   client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
     *         .thenAccept(resp -> LOG.info("Response: " + resp.body()));
     * </pre>
     *
     * @param jsonData the JSON string produced by {@link #doScan(PsiClass)}
     */
    @SuppressWarnings("unused")
    public static void syncToDocSystem(@NotNull String jsonData) {
        // TODO: implement AI / documentation API integration
    }
}
