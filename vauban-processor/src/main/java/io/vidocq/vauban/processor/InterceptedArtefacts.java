/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.processor;

import io.vidocq.vauban.core.interceptor.InterceptedEmitter;
import io.vidocq.vauban.processor.codegen.GeneratedClass;
import io.vidocq.vauban.processor.codegen.interceptor.InterceptedShapeFromElements;
import io.vidocq.vauban.processor.codegen.interceptor.InterceptedSourceRenderer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.util.ArrayList;

/**
 * The {@code <bean>$$Intercepted} subclasses the processor generates: which beans are interception targets, and the
 * subclass of each, as Java source or, when source cannot name a type, as bytecode.
 */
final class InterceptedArtefacts {

    private static final String JAVA_LANG_OBJECT = "java.lang.Object";

    private final ProcessingEnvironment processingEnv;
    private final GeneratedOutput output;
    private final TypeLookup typeLookup;

    InterceptedArtefacts(ProcessingEnvironment processingEnv, GeneratedOutput output, TypeLookup typeLookup) {
        this.processingEnv = processingEnv;
        this.output = output;
        this.typeLookup = typeLookup;
    }

    /**
     * Pre-generates the {@code <bean>$$Intercepted} subclass for intercepted targets, so the
     * runtime needs no reflective class definition (hence no {@code opens … to
     * io.vidocq.vauban.core}) on the strict module path. Mirrors the Maven plugin but
     * builds the shape from javac Elements instead of a loaded Class. Top-level,
     * non-final targets only; anything else falls back to runtime generation.
     *
     * @return whether the subclass was generated
     */
    boolean generate(String fqn) {
        if (!typeLookup.isTopLevelType(fqn)) {
            return false;
        }
        var typeElement = processingEnv.getElementUtils().getTypeElement(fqn);
        if (typeElement == null || !isInterceptedTarget(typeElement)) {
            return false;
        }
        try {
            var unnameable = new ArrayList<String>();
            var shape = InterceptedShapeFromElements.from(typeElement,
                    processingEnv.getElementUtils(), processingEnv.getTypeUtils(),
                    omitted -> output.warnOmitted(omitted, typeElement), unnameable::add);
            if (unnameable.isEmpty()) {
                // Emit the subclass as readable Java source (not bytecode). The bytecode
                // emitter is kept for the runtime classpath fallback, the Maven plugin,
                // and the subclass Java source cannot write (below).
                var gen = InterceptedSourceRenderer.render(shape,
                        typeElement.getQualifiedName().toString());
                output.writeSourceFile(gen.className(), gen.source());
            } else {
                // An inherited method's signature names a type this package cannot
                // (BUG-20261004-09): no source override can declare it. The bytecode
                // subclass overrides by descriptor; the source provider instantiates
                // it through its own lookup (noteBytecode records it).
                output.generateClass(new GeneratedClass(shape.subclassName(),
                        InterceptedEmitter.emit(shape)));
                output.noteBytecode(shape.subclassName(), unnameable, typeElement);
            }
            return true;
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Could not pre-generate " + fqn + "$$Intercepted"
                            + " (the runtime will generate it): " + e.getMessage());
            return false;
        }
    }

    /**
     * A managed bean is an interception TARGET (needs a {@code $$Intercepted} subclass) when it is
     * non-final and carries an interceptor binding — at the class level (including {@code @Inherited}
     * bindings from supertypes, via {@code getAllAnnotationMirrors}) or on any instance method.
     *
     * <p>Detection uses the javac {@code Elements} API rather than the indexer model: a custom
     * binding annotation defined in (or brought into) the module isn't necessarily in the Vauban
     * index, but its meta-{@code @InterceptorBinding} is always resolvable from the compiler symbol
     * table. This also covers method-level bindings, as the Maven plugin does through
     * {@code InterceptionTargets}, including those of an inherited method: a superclass method or an
     * interface default method the bean does not override (CDI 4.1 §4.2) — the container intercepts such a bean, and on the strict
     * module path it cannot define the subclass itself.
     */
    private boolean isInterceptedTarget(TypeElement beanElement) {
        if (beanElement.getModifiers().contains(Modifier.FINAL) || isInterceptorClass(beanElement)) return false;
        for (var am : processingEnv.getElementUtils().getAllAnnotationMirrors(beanElement)) {
            if (isInterceptorBinding(am)) return true;
        }
        for (var enclosed : beanElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD && hasMethodLevelBinding((ExecutableElement) enclosed)) {
                return true;
            }
        }
        return hasInheritedMethodLevelBinding(beanElement);
    }

    /**
     * An {@code @Interceptor} class carries the interceptor binding to associate itself with its targets,
     * but it is never itself an interception target — no {@code $$Intercepted} is generated for it.
     */
    private static boolean isInterceptorClass(TypeElement beanElement) {
        for (var am : beanElement.getAnnotationMirrors()) {
            if (am.getAnnotationType().toString().equals("jakarta.interceptor.Interceptor")) return true;
        }
        return false;
    }

    /**
     * Whether a method the bean inherits, as getAllMembers lists them, carries an interceptor binding:
     * overridden ones are left out, so the binding of a method a class of the bean overrides does not
     * count. The bean's own methods and those of {@code java.lang.Object} are not looked at.
     */
    private boolean hasInheritedMethodLevelBinding(TypeElement beanElement) {
        try {
            for (var inherited : ElementFilter.methodsIn(
                    processingEnv.getElementUtils().getAllMembers(beanElement))) {
                var owner = inherited.getEnclosingElement();
                boolean examined = !owner.equals(beanElement)
                        && !(owner instanceof TypeElement type && type.getQualifiedName().contentEquals(JAVA_LANG_OBJECT));
                if (examined && hasMethodLevelBinding(inherited)) return true;
            }
        } catch (RuntimeException incomplete) {
            // A binary supertype or annotation the compiler cannot complete: leave the bean to the
            // run-time fallback, as for any bean not detected here.
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Could not inspect the inherited methods of " + beanElement
                            + " for interceptor bindings: " + incomplete.getMessage());
        }
        return false;
    }

    /** A non-static, non-private method annotated with an interceptor binding. */
    private static boolean hasMethodLevelBinding(ExecutableElement method) {
        if (method.getModifiers().contains(Modifier.STATIC)
                || method.getModifiers().contains(Modifier.PRIVATE)) return false;
        for (var am : method.getAnnotationMirrors()) {
            if (isInterceptorBinding(am)) return true;
        }
        return false;
    }

    /**
     * An interceptor binding is an annotation type itself meta-annotated {@code @InterceptorBinding}.
     *
     * <p>Detected by NAME over the annotation type's own meta-mirrors, NOT via
     * {@code getAnnotation(InterceptorBinding.class)}. The class-literal form forces javac to complete
     * the {@code jakarta.interceptor.InterceptorBinding} symbol; that completion throws a
     * {@code CompletionFailure} when the binding marker is declared in a dependency module that only
     * {@code requires static jakarta.interceptor} (e.g. heisenberg's {@code @FaultToleranceBinding}) and
     * is processed from a downstream module on a strict module path — the marker module's read edge to
     * {@code jakarta.interceptor} is absent from the consumer's module graph. Reading a meta-mirror's
     * type name only touches the constant pool, so it never triggers completion — the same name-based
     * pattern already used for the {@code @Interceptor} check in {@link #isInterceptedTarget}.
     */
    private static boolean isInterceptorBinding(javax.lang.model.element.AnnotationMirror am) {
        for (var meta : am.getAnnotationType().asElement().getAnnotationMirrors()) {
            if (meta.getAnnotationType().toString().equals("jakarta.interceptor.InterceptorBinding")) {
                return true;
            }
        }
        return false;
    }
}
