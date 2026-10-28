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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.proxy.ClientProxyEmitter;
import io.vidocq.vauban.core.proxy.ProducerProxyEligibility;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.processor.codegen.ElementTypeView;
import io.vidocq.vauban.processor.codegen.GeneratedClass;
import io.vidocq.vauban.processor.codegen.proxy.ClientProxyShapeFromElements;
import io.vidocq.vauban.processor.codegen.proxy.ClientProxySourceRenderer;
import io.vidocq.vauban.processor.codegen.proxy.InterfaceProxySourceRenderer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The client proxies the processor generates: the proxy of a normal-scoped managed bean, and the build-time proxy
 * of a normal-scoped producer, or, when its produced type is not proxyable from the producer's package, the placed
 * proxy shipped for the Vauban class loader and the report of why (issue #42).
 */
final class ClientProxyArtefacts {

    static final String CLIENT_PROXY_SUFFIX = "_ClientProxy";
    /** Where the co-located proxy bytes of a listed produced type are shipped (#42 Stage 4). */
    private static final String PLACED_PREFIX = "META-INF/vauban/placed/";
    private static final String LOG_PREFIX = "[Vauban] ";
    private static final String JAVA_LANG_OBJECT = "java.lang.Object";

    private final ProcessingEnvironment processingEnv;
    private final GeneratedOutput output;

    /** Outcome of the placed-proxy emission, per produced type. */
    private final Map<String, Boolean> placedProxyOutcomes = new HashMap<>();

    ClientProxyArtefacts(ProcessingEnvironment processingEnv, GeneratedOutput output) {
        this.processingEnv = processingEnv;
        this.output = output;
    }

    /** The client proxy of a normal-scoped managed bean. */
    void generateForBean(String proxyBeanFqn, Generation generation) {
        // The index carries the binary name: a nested bean is app.Outer$Inner, and a
        // top-level class may have a '$' in its own name (BUG-20261004-11).
        var proxyTypeElement = TypeLookup.typeElementByBinaryName(processingEnv.getElementUtils(), proxyBeanFqn);
        if (proxyTypeElement != null && TypeLookup.hasNonPrivateCtor(proxyTypeElement)) {
            generateSubclassingClientProxy(proxyTypeElement);
            generation.clientProxyFqns.add(proxyBeanFqn + CLIENT_PROXY_SUFFIX);
        } else if (proxyTypeElement != null) {
            // Private constructors only: no source can call one, so the proxy is bytecode, chaining
            // to the (ProxyLink) constructor the weaver adds (vauban#24). The runtime instantiates it
            // reflectively, its package staying opened or exported as before. It sits in the bean's
            // package, so its shape is the co-located one: the inherited members and the interface
            // default methods are forwarded too, not only the declared ones (BUG-20261004-11).
            var shape = ClientProxyShapeFromElements.fromColocated(proxyTypeElement,
                    processingEnv.getElementUtils(), processingEnv.getTypeUtils());
            output.generateClass(new GeneratedClass(
                    shape.proxyClassName(), ClientProxyEmitter.emit(shape)));
        } else {
            // The index is built from this compilation's elements, so its binary name always
            // resolves; should it not, no build-time proxy is better than one forwarding the
            // declared methods only, and the container generates it at run time, announced at boot
            // (vauban#227).
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, LOG_PREFIX
                    + "no type element for " + proxyBeanFqn
                    + "; its client proxy is left to the run-time fallback.");
        }
    }

    /**
     * SOURCE proxy: the sibling _VaubanComponents provider does
     * {@code new <Bean>_ClientProxy(delegate)} in-module (createClientProxy), so the bean package
     * needs no opens/exports for proxy creation. Source (not bytecode) so the
     * provider source can reference it by name (resolved in a later APT round).
     * The proxy ctor calls the simplest non-private super ctor with default values,
     * so beans with only an injected (arg-bearing) constructor are covered too.
     *
     * <p>A nested bean takes this route too. Its proxy keeps the binary name —
     * Outer$Inner_ClientProxy — which is a perfectly legal top-level class name
     * in source ('$' is an identifier character), so the file compiles and the
     * provider can name it. What it must NOT be is a member of Outer: nothing
     * can add one to a class that already exists.
     */
    private void generateSubclassingClientProxy(TypeElement proxyTypeElement) {
        var proxyPackage = processingEnv.getElementUtils().getPackageOf(proxyTypeElement);
        var unnameable = ClientProxyShapeFromElements.unnameableForwards(proxyTypeElement,
                proxyPackage, processingEnv.getElementUtils(), processingEnv.getTypeUtils());
        var throughHandles = ClientProxyShapeFromElements.methodHandleForwards(proxyTypeElement,
                proxyPackage, processingEnv.getElementUtils(), processingEnv.getTypeUtils());
        if (unnameable.isEmpty() && throughHandles.isEmpty()) {
            var proxyShape = ClientProxyShapeFromElements.from(proxyTypeElement, proxyPackage,
                    processingEnv.getElementUtils(), processingEnv.getTypeUtils(),
                    omitted -> output.warnOmitted(omitted, proxyTypeElement));
            var gen = ClientProxySourceRenderer.render(proxyShape,
                    proxyTypeElement.getQualifiedName().toString());
            output.writeSourceFile(gen.className(), gen.source());
        } else {
            // An inherited method's signature names a type this package cannot
            // (BUG-20261004-09): no source override can declare it. The bytecode
            // proxy carries that type in a descriptor only; the source provider
            // instantiates it through its own lookup (noteBytecode records it).
            var proxyShape = ClientProxyShapeFromElements.fromErased(proxyTypeElement, proxyPackage,
                    processingEnv.getElementUtils(), processingEnv.getTypeUtils(),
                    omitted -> output.warnOmitted(omitted, proxyTypeElement));
            output.generateClass(new GeneratedClass(proxyShape.proxyClassName(),
                    ClientProxyEmitter.emit(proxyShape)));
            output.noteBytecode(proxyShape.proxyClassName(), unnameable, throughHandles, proxyTypeElement);
        }
    }

    /** The build-time client proxy of a normal-scoped producer, or the report of why it has none. */
    void generateForProducer(BeanDescriptor bean, Generation generation) {
        var producedType = resolveProducedTypeElement(bean);
        if (producedType == null) {
            // The produced type is an interface (or unresolvable). For a public interface,
            // emit a build-time static proxy `implements <Iface>` in the producer's package
            // (issue #42) — no runtime java.lang.reflect.Proxy, no reflection.
            var producedIface = resolveProducedInterfaceElement(bean);
            if (producedIface != null) {
                generateInterfaceProducerProxy(bean, producedIface, generation);
            }
            return;
        }
        var producedFqn =
                processingEnv.getElementUtils().getBinaryName(producedType).toString();
        var verdict = ProducerProxyEligibility.of(ElementTypeView.of(producedType, processingEnv.getTypeUtils()));
        if (verdict.eligible()) {
            generateClassProducerProxy(bean, producedType, producedFqn, generation);
        } else {
            reportIneligibleProducedType(producedType, producedFqn, verdict, generation);
        }
    }

    /** The static proxy {@code implements <Iface>} of a producer of a public interface (issue #42). */
    private void generateInterfaceProducerProxy(BeanDescriptor bean, TypeElement producedIface,
            Generation generation) {
        var producerPkg = TypeLookup.packageOfFqn(bean.beanClass().value());
        var ifaceFqn = processingEnv.getElementUtils()
                .getBinaryName(producedIface).toString();
        var ifaceSimple = ifaceFqn.substring(ifaceFqn.lastIndexOf('.') + 1)
                .replace('$', '_');
        var proxyBinaryName = producerProxyName(producerPkg, ifaceSimple, ifaceFqn);
        var gen = InterfaceProxySourceRenderer.render(producedIface, proxyBinaryName,
                processingEnv.getElementUtils(), processingEnv.getTypeUtils());
        output.writeSourceFile(gen.className(), gen.source());
        var key = ifaceFqn + CLIENT_PROXY_SUFFIX;
        generation.addProducerProxy(producerPkg, key, proxyBinaryName);
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "[Vauban] Generated build-time interface proxy " + proxyBinaryName
                        + " for " + ifaceFqn + " (key " + key + ") — no reflect.Proxy.");
    }

    /** {@code <producer package>.<simple name>$$<hash of the produced type>_ClientProxy}. */
    private static String producerProxyName(String producerPkg, String producedSimple, String producedFqn) {
        return (producerPkg.isEmpty() ? "" : producerPkg + ".")
                + producedSimple + "$$"
                + Integer.toHexString(producedFqn.hashCode()) + CLIENT_PROXY_SUFFIX;
    }

    /**
     * The produced type's proxy must live in the type's own package. Record it in
     * the placement manifest (META-INF/vauban/required-opens.list): in a Vauban
     * layer the class loader that owns that package places the proxy there (#42
     * Stage 4); elsewhere the boot-time OpensApplier may open the package for the
     * reflective fallback (Stage 3b, opt-in).
     */
    private void reportIneligibleProducedType(TypeElement producedType, String producedFqn,
            ProducerProxyEligibility.Reason verdict, Generation generation) {
        generation.requiredOpens.add(producedFqn);
        var placed = isPlaceable(verdict) && writePlacedProxy(producedType, producedFqn);
        // Severity is configurable via -Avauban.producerProxy (error|warn|note,
        // default note) so a project can enforce zero-fallback.
        processingEnv.getMessager().printMessage(
                producerProxyDiagnosticKind(),
                "[Vauban] Producer of " + producedFqn + " needs its client proxy inside "
                        + TypeLookup.packageOfFqn(producedFqn) + " (" + verdict + "). "
                        + (placed
                                ? "Its co-located proxy is shipped under "
                                        + PLACED_PREFIX + " and the Vauban class loader "
                                        + "defines it there when the application runs in a "
                                        + "Vauban layer — zero opens, no agent: start through "
                                        + "io.vidocq.vauban.classloader.Launch (or Vidocq.run). "
                                : "")
                        + "On a bare module path the runtime generates it reflectively, "
                        + "which needs `opens " + TypeLookup.packageOfFqn(producedFqn)
                        + " to io.vidocq.vauban.core;`. To keep zero opens there: produce an "
                        + "interface type, make the produced type fully public (public "
                        + "non-final class, public overridable methods, a public/protected "
                        + "constructor), add the (ProxyLink) constructor upstream, run "
                        + "vauban:enhance-dependencies, or open the package.");
    }

    /** The build-time proxy, in the producer's package, of a fully-public produced class (issue #42). */
    private void generateClassProducerProxy(BeanDescriptor bean, TypeElement producedType, String producedFqn,
            Generation generation) {
        var producerPkg = TypeLookup.packageOfFqn(bean.beanClass().value());
        // Rendered in the producer's package: an interface the proxy forwards a shadowed
        // default method through must be nameable from there (BUG-20261004-08).
        var producerElement = TypeLookup.typeElementByBinaryName(processingEnv.getElementUtils(),
                bean.beanClass().value());
        // Unknown package (null): only an interface nameable from anywhere qualifies.
        var proxyPackage = producerElement != null
                ? processingEnv.getElementUtils().getPackageOf(producerElement)
                : processingEnv.getElementUtils().getPackageElement(producerPkg);
        // A method the proxy leaves out is reported on the producer method, which the user
        // wrote: the produced type is often a class of a dependency, with no source.
        var producerSite = producerElement != null ? producerSite(bean, producerElement) : producedType;
        var producedSimple = producedFqn.substring(producedFqn.lastIndexOf('.') + 1)
                .replace('$', '_');
        var proxyBinaryName = producerProxyName(producerPkg, producedSimple, producedFqn);
        var unnameable = ClientProxyShapeFromElements.unnameableForwards(producedType, proxyPackage,
                processingEnv.getElementUtils(), processingEnv.getTypeUtils());
        var throughHandles = ClientProxyShapeFromElements.methodHandleForwards(producedType, proxyPackage,
                processingEnv.getElementUtils(), processingEnv.getTypeUtils());
        if (unnameable.isEmpty() && throughHandles.isEmpty()) {
            var shape = ClientProxyShapeFromElements.fromProduced(producedType, proxyPackage,
                    processingEnv.getElementUtils(), processingEnv.getTypeUtils(),
                    omitted -> output.warnOmitted(omitted, producerSite));
            // Named by its canonical name: a '$' may be part of its own name (BUG-20261007-01).
            var gen = ClientProxySourceRenderer.renderAt(shape, proxyBinaryName,
                    producedType.getQualifiedName().toString());
            output.writeSourceFile(gen.className(), gen.source());
        } else {
            // As for a bean's proxy: a signature the producer's package cannot write in
            // source is carried by a bytecode descriptor instead (BUG-20261004-09).
            var shape = ClientProxyShapeFromElements.fromProducedErased(producedType, proxyPackage,
                    processingEnv.getElementUtils(), processingEnv.getTypeUtils(),
                    omitted -> output.warnOmitted(omitted, producerSite));
            output.generateClass(new GeneratedClass(proxyBinaryName,
                    ClientProxyEmitter.emitAt(shape, proxyBinaryName)));
            output.noteBytecode(proxyBinaryName, unnameable, throughHandles, producerSite);
        }
        var key = producedFqn + CLIENT_PROXY_SUFFIX;
        generation.addProducerProxy(producerPkg, key, proxyBinaryName);
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "[Vauban] Generated build-time producer proxy " + proxyBinaryName + " for "
                        + producedFqn + " (key " + key + ") — zero opens, zero runtime defineClass.");
    }

    /**
     * Whether a produced type with this verdict can be proxied at all once the proxy sits in the
     * type's own package: a non-public overridable member and an inaccessible constructor are
     * cross-package obstacles only. A final or sealed class, a final method or an abstract class
     * are unproxyable anywhere (CDI 4.1 §3.10), so nothing is shipped for them.
     */
    private static boolean isPlaceable(ProducerProxyEligibility.Reason verdict) {
        return verdict.placeable();
    }

    /**
     * Emit the co-located client proxy of {@code producedType} as bytecode and ship it as a
     * resource of this bean archive (issue #42, Stage 4). The processor can neither write a source
     * proxy into another module's package (split package) nor define a class there; a resource
     * it can. The Vauban class loader, which owns that package when the application runs in a
     * Vauban layer, defines the class into it at runtime — zero {@code opens}, no agent, no
     * rewritten jar — and the cdi-proxifier retargets it onto the {@code (ProxyLink)} entry
     * constructor it weaves into the produced type, so the third-party constructor never runs.
     *
     * @return whether the bytes were written
     */
    private boolean writePlacedProxy(TypeElement producedType, String producedFqn) {
        // Once per produced type: several producers of the same type (qualifiers) are valid CDI,
        // and creating the same resource twice fails with FilerException.
        return placedProxyOutcomes.computeIfAbsent(producedFqn,
                fqn -> emitPlacedProxy(producedType, fqn));
    }

    private boolean emitPlacedProxy(TypeElement producedType, String producedFqn) {
        try {
            // Co-located shape: the placed proxy lives in the produced type's package, so it can —
            // and must — also override the non-public members the type inherits.
            var shape = ClientProxyShapeFromElements.fromColocated(producedType,
                    processingEnv.getElementUtils(), processingEnv.getTypeUtils());
            var bytes = ClientProxyEmitter.emit(shape);
            var path = PLACED_PREFIX + shape.proxyClassName().replace('.', '/') + ".class";
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", path);
            try (var out = resource.openOutputStream()) {
                out.write(bytes);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Could not ship the placed proxy for " + producedFqn + ": " + e
                            + " — the runtime fallback (opens) remains available.");
            return false;
        }
    }

    /**
     * The produced concrete class type of a producer bean as a {@link TypeElement}, or {@code null}
     * when the produced type is an interface (handled at runtime by {@code java.lang.reflect.Proxy},
     * which needs only {@code exports}) or is not resolvable at APT time. Mirrors the runtime
     * {@code InterceptorBeanWrapper.resolveProxyTargetClass}: prefer a concrete, non-{@code Object}
     * type among the bean types.
     */
    private TypeElement resolveProducedTypeElement(BeanDescriptor bean) {
        var elements = processingEnv.getElementUtils();
        for (var t : bean.types()) {
            // By binary name: a nested produced type is app.Outer$Inner here (BUG-20261007-01).
            var te = t instanceof TypeInfo.ClassType(DotName name) && !JAVA_LANG_OBJECT.equals(name.value())
                    ? TypeLookup.typeElementByBinaryName(elements, name.value()) : null;
            if (te != null && te.getKind() == ElementKind.CLASS) {
                return te;
            }
        }
        return null;
    }

    /**
     * The produced <strong>interface</strong> type of a producer bean as a {@link TypeElement}, or
     * {@code null} when the produced type is not a public interface. Used to emit a build-time static
     * interface proxy (issue #42) instead of a runtime {@code java.lang.reflect.Proxy}. Mirrors the
     * runtime selection of the produced type, but keeps only a public interface.
     */
    private TypeElement resolveProducedInterfaceElement(BeanDescriptor bean) {
        var elements = processingEnv.getElementUtils();
        for (var t : bean.types()) {
            // By binary name: a nested produced type is app.Outer$Inner here (BUG-20261007-01).
            var te = t instanceof TypeInfo.ClassType(DotName name) && !JAVA_LANG_OBJECT.equals(name.value())
                    ? TypeLookup.typeElementByBinaryName(elements, name.value()) : null;
            if (te != null && te.getKind() == ElementKind.INTERFACE
                    && te.getModifiers().contains(Modifier.PUBLIC)) {
                return te;
            }
        }
        return null;
    }

    /**
     * Diagnostic severity for a producer whose produced class type is not build-time proxyable,
     * from {@code -Avauban.producerProxy} ({@code error} | {@code warn} | {@code note}). Defaults to
     * {@code note}: the runtime fallback still works (it needs an {@code opens} on the module path),
     * so this is not an error unless a project opts into enforcing zero fallback.
     */
    private Diagnostic.Kind producerProxyDiagnosticKind() {
        var opt = processingEnv.getOptions().get("vauban.producerProxy");
        if (opt == null) {
            return Diagnostic.Kind.NOTE;
        }
        return switch (opt.trim().toLowerCase(Locale.ROOT)) {
            case "error" -> Diagnostic.Kind.ERROR;
            case "warn", "warning" -> Diagnostic.Kind.WARNING;
            default -> Diagnostic.Kind.NOTE;
        };
    }

    /**
     * The producer method or field {@code bean} stands for, among the members of {@code declaring}
     * annotated {@code @Produces}, found by the name its id carries ({@code Class#method} or
     * {@code Class.field}); {@code declaring} itself when none matches.
     */
    private javax.lang.model.element.Element producerSite(BeanDescriptor bean, TypeElement declaring) {
        var id = bean.id().value();
        var prefix = bean.beanClass().value();
        char separator = bean.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD ? '#' : '.';
        if (id.length() <= prefix.length() + 1 || !id.startsWith(prefix) || id.charAt(prefix.length()) != separator) {
            return declaring;
        }
        var memberName = id.substring(prefix.length() + 1);
        var kind = bean.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD ? ElementKind.METHOD : ElementKind.FIELD;
        for (var member : declaring.getEnclosedElements()) {
            if (member.getKind() == kind && member.getSimpleName().contentEquals(memberName)
                    && member.getAnnotationMirrors().stream().anyMatch(am ->
                            am.getAnnotationType().toString().equals("jakarta.enterprise.inject.Produces"))) {
                return member;
            }
        }
        return declaring;
    }
}
