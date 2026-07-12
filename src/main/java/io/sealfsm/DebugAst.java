package io.sealfsm;

import io.sealfsm.detect.SpoonCompat;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtReturn;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.Set;

/**
 * Dumps the Spoon AST so you can see exactly what the tool's analysis works with.
 * Useful for understanding why a transition was or wasn't resolved, or why
 * classification produced a particular result.
 *
 * Usage from IDEA:
 *   Run with argument: examples/traffic     (or door, shape, examples)
 *   Add --full for raw Spoon toString() of each type body
 *   Add --returns to see every return expression and its AST node type
 *
 * Examples:
 *   DebugAst examples/traffic
 *   DebugAst examples/door --returns
 *   DebugAst examples/traffic --full
 */
public class DebugAst {

    public static void main(String[] args) {
        String src = "examples/traffic";
        boolean full = false;
        boolean showReturns = false;

        for (String arg : args) {
            switch (arg) {
                case "--full" -> full = true;
                case "--returns" -> showReturns = true;
                default -> src = arg;
            }
        }

        System.out.println("═══════════════════════════════════════════════");
        System.out.println(" Spoon AST dump — source: " + src);
        System.out.println("═══════════════════════════════════════════════\n");

        Launcher launcher = new Launcher();
        launcher.addInputResource(src);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        CtModel model = launcher.getModel();

        for (CtType<?> type : model.getAllTypes()) {
            System.out.println("╔══════════════════════════════════════════════");
            System.out.printf("║ %s%n", type.getQualifiedName());
            System.out.printf("║ AST node type: %s%n", type.getClass().getSimpleName());
            System.out.printf("║ sealed: %s%n", SpoonCompat.isSealed(type));
            System.out.printf("║ modifiers: %s%n", type.getModifiers());

            // Permitted types (if sealed)
            if (SpoonCompat.isSealed(type)) {
                Set<CtTypeReference<?>> permitted = SpoonCompat.permittedTypes(type);
                System.out.printf("║ permits (%d):%n", permitted.size());
                for (CtTypeReference<?> p : permitted) {
                    CtType<?> decl = p.getTypeDeclaration();
                    System.out.printf("║   %s  →  resolved=%s  sealed=%s%n",
                            p.getQualifiedName(),
                            decl != null,
                            decl != null && SpoonCompat.isSealed(decl));
                }
            }

            // Supertype chain
            CtTypeReference<?> superclass = type.getSuperclass();
            if (superclass != null) {
                System.out.printf("║ extends: %s%n", superclass.getQualifiedName());
            }
            if (!type.getSuperInterfaces().isEmpty()) {
                System.out.printf("║ implements: %s%n",
                        type.getSuperInterfaces().stream()
                                .map(CtTypeReference::getQualifiedName)
                                .reduce((a, b) -> a + ", " + b).orElse(""));
            }

            // Fields
            for (CtField<?> field : type.getFields()) {
                System.out.printf("║ field: %s %s",
                        field.getType() != null ? field.getType().getSimpleName() : "?",
                        field.getSimpleName());
                if (field.getDefaultExpression() != null) {
                    System.out.printf(" = %s  [%s]",
                            field.getDefaultExpression(),
                            field.getDefaultExpression().getClass().getSimpleName());
                }
                System.out.println();
            }

            // Methods — signature + return type + body shape
            for (CtMethod<?> method : type.getMethods()) {
                String params = method.getParameters().stream()
                        .map(p -> p.getType().getSimpleName() + " " + p.getSimpleName())
                        .reduce((a, b) -> a + ", " + b).orElse("");
                System.out.printf("║ method: %s %s(%s)%n",
                        method.getType() != null ? method.getType().getSimpleName() : "void",
                        method.getSimpleName(),
                        params);

                // Show return expressions and their AST types
                if (showReturns) {
                    var returns = method.getElements(new TypeFilter<>(CtReturn.class));
                    for (CtReturn<?> ret : returns) {
                        var expr = ret.getReturnedExpression();
                        if (expr != null) {
                            System.out.printf("║   return: %s%n", expr);
                            System.out.printf("║     AST node: %s%n", expr.getClass().getSimpleName());
                            if (expr.getType() != null) {
                                System.out.printf("║     type: %s%n", expr.getType().getQualifiedName());
                            }
                        }
                    }
                }
            }

            // Full source (optional)
            if (full) {
                System.out.println("║");
                System.out.println("║ ── Full Spoon-reconstructed source ──");
                for (String line : type.toString().split("\n")) {
                    System.out.println("║   " + line);
                }
            }

            System.out.println("╚══════════════════════════════════════════════\n");
        }
    }
}
