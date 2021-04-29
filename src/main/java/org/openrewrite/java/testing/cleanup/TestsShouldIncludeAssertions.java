/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.testing.cleanup;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.Arrays;
import java.util.List;

/**
 * For Tests not having any assertions, wrap the statements with JUnit 5's Assertions.assertThrowDoesNotThrow.
 *
 * <a href="https://rules.sonarsource.com/java/tag/tests/RSPEC-2699">Sonar Source RSPEC-2699</a>
 */
@Incubating(since = "1.2.0")
@Value
@EqualsAndHashCode(callSuper = true)
public class TestsShouldIncludeAssertions extends Recipe {

    private static final AnnotationMatcher JUNIT_JUPITER_TEST = new AnnotationMatcher("@org.junit.jupiter.api.Test");

    private static final String THROWING_SUPPLIER_FQN = "org.junit.jupiter.api.function.ThrowingSupplier";
    private static final String ASSERTIONS_FQN = "org.junit.jupiter.api.Assertions";

    private static final String ASSERTIONS_DOES_NOT_THROW_FQN = "org.junit.jupiter.api.Assertions.assertDoesNotThrow";
    private static final String ASSERT_DOES_NOT_THROW = "assertDoesNotThrow";

    private static final ThreadLocal<JavaParser> ASSERTIONS_PARSER = ThreadLocal.withInitial(() ->
            JavaParser.fromJavaVersion().dependsOn(Arrays.asList(
                    Parser.Input.fromString(
                            "package org.junit.jupiter.api.function;" +
                                    "public interface ThrowingSupplier<T> {T get() throws Throwable;}"
                    ),
                    Parser.Input.fromString(
                            "package org.junit.jupiter.api;" +
                                    "import org.junit.jupiter.api.function.ThrowingSupplier;" +
                                    "class AssertDoesNotThrow {" +
                                    "   static <T> T assertDoesNotThrow(ThrowingSupplier<T> supplier) {\n" +
                                    "       return (T)(Object)null;\n" +
                                    "   }" +
                                    "}"
                    )
            )).build());

    @Option(displayName = "Assertions",
            description = "List of fully qualified classes and or methods used for identifying assertion statements.",
            example = "org.assertj.core.api, org.junit.jupiter.api.Assertions, org.hamcrest.MatcherAssert, org.mockito.Mockito.verify")
    List<String> assertions;

    @Override
    public String getDisplayName() {
        return "Include an assertion in tests";
    }

    @Override
    public String getDescription() {
        return "For tests not having any assertions, wrap the statements with JUnit Jupiter's `Assertions#assertThrowDoesNotThrow(..)`.";
    }

    @Override
    public Validated validate() {
        Validated validated = super.validate()
                .and(Validated.required("assertions", assertions));
        if (validated.isValid()) {
            validated = validated.and(Validated.test(
                    "assertions",
                    "Assertions must not be empty and at least contain org.junit.jupiter.api.Assertions",
                    assertions,
                    a -> a.stream().filter(ASSERTIONS_FQN::equals).findAny().isPresent()));
        }
        return validated;
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TestIncludesAssertionsVisitor();
    }

    private class TestIncludesAssertionsVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext executionContext) {
            if ((!methodIsTest(method) || method.getBody() == null)
                    || methodHasAssertion(method.getBody().getStatements())) {
                return method;
            }

            J.MethodDeclaration md = super.visitMethodDeclaration(method, executionContext);
            J.Block body = md.getBody();
            if (body != null) {
                StringBuilder t = new StringBuilder("{\nassertDoesNotThrow(() -> {");
                body.getStatements().forEach(st -> t.append(st.print()).append(";"));
                t.append("});\n}");

                body = body.withTemplate(template(t.toString())
                                .imports(THROWING_SUPPLIER_FQN)
                                .staticImports(ASSERTIONS_DOES_NOT_THROW_FQN)
                                .javaParser(ASSERTIONS_PARSER.get()).build(),
                        body.getCoordinates().replace());
                md = maybeAutoFormat(md, md.withBody(body), executionContext, getCursor().dropParentUntil(J.class::isInstance));
                maybeAddImport(ASSERTIONS_FQN, ASSERT_DOES_NOT_THROW);
            }
            return md;
        }

        private boolean methodIsTest(J.MethodDeclaration methodDeclaration) {
            return methodDeclaration.getLeadingAnnotations().stream()
                    .filter(JUNIT_JUPITER_TEST::matches)
                    .findAny().isPresent();
        }

        private boolean methodHasAssertion(List<Statement> statements) {
            return statements.stream()
                    .filter(J.MethodInvocation.class::isInstance)
                    .map(J.MethodInvocation.class::cast)
                    .filter(this::isAssertion).findAny().isPresent();
        }

        private boolean isAssertion(J.MethodInvocation methodInvocation) {
            if (methodInvocation.getType() == null) {
                return false;
            }
            String fqt = methodInvocation.getType().getDeclaringType().getFullyQualifiedName();
            for (String assertionClassOrPackage : assertions) {
                if (fqt.startsWith(assertionClassOrPackage)) {
                    return true;
                }
            }

            if (methodInvocation.getSelect() != null && methodInvocation.getSelect() instanceof J.MethodInvocation
                    && ((J.MethodInvocation) methodInvocation.getSelect()).getType() != null) {
                J.MethodInvocation selectMethod = (J.MethodInvocation) methodInvocation.getSelect();
                if (selectMethod.getType() != null) {
                    String select = selectMethod.getType().getDeclaringType().getFullyQualifiedName() + "." + selectMethod.getSimpleName();
                    for (String assertMethod : assertions) {
                        if (select.equals(assertMethod)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }
}
