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
package io.vidocq.vauban.weaver;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.util.Map;
import java.util.Set;

/**
 * Byte-level analysis shared by every load-time weaving client (the container's
 * {@code LoadTimeWeaving} agent tier, the class-loader {@code cdi-proxifier} transformer):
 * decides from class-file bytes alone — never loading a class — whether a class is a
 * normal-scoped bean candidate, and how a woven {@code (ProxyLink)} marker must chain to
 * its superclass.
 *
 * <p>All lookups of <em>other</em> classes (meta-annotations, superclasses) go through a
 * caller-supplied {@link ByteResolver}, so the same analysis runs against a class loader's
 * resources, an {@code ArchiveReader}, or any other byte source.
 */
public final class BeanWeavingAnalysis {

    /** Resolves the class-file bytes of a binary name, or {@code null} when unknown. */
    @FunctionalInterface
    public interface ByteResolver {
        byte[] bytesOf(String binaryName);
    }

    private static final Set<String> STANDARD_NORMAL_SCOPE_DESCRIPTORS = Set.of(
            "Ljakarta/enterprise/context/ApplicationScoped;",
            "Ljakarta/enterprise/context/RequestScoped;",
            "Ljakarta/enterprise/context/SessionScoped;",
            "Ljakarta/enterprise/context/ConversationScoped;");
    private static final String NORMAL_SCOPE_DESCRIPTOR = "Ljakarta/enterprise/context/NormalScope;";
    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ABSTRACT = 0x0400;

    private BeanWeavingAnalysis() {}

    /**
     * Byte-level normal-scope check: the class carries one of the standard normal-scope
     * annotations, or an annotation whose own class file is meta-annotated
     * {@code @NormalScope}. Interfaces and abstract classes are never proxied.
     *
     * @param scopeCache caller-provided memo for custom-annotation lookups
     *                   (descriptor → verdict), typically scoped to one detection pass
     * @throws IllegalArgumentException when {@code bytes} are not class-file bytes
     */
    public static boolean isNormalScopedBeanClass(byte[] bytes, ByteResolver resolver,
            Map<String, Boolean> scopeCache) {
        var model = ClassFile.of().parse(bytes);
        if ((model.flags().flagsMask() & (ACC_INTERFACE | ACC_ABSTRACT)) != 0) return false;
        for (var attribute : model.attributes()) {
            if (!(attribute instanceof RuntimeVisibleAnnotationsAttribute annotations)) continue;
            for (var annotation : annotations.annotations()) {
                var descriptor = annotation.className().stringValue();
                if (STANDARD_NORMAL_SCOPE_DESCRIPTORS.contains(descriptor)) return true;
                if (isCustomNormalScope(descriptor, resolver, scopeCache)) return true;
            }
        }
        return false;
    }

    private static boolean isCustomNormalScope(String descriptor, ByteResolver resolver,
            Map<String, Boolean> scopeCache) {
        return scopeCache.computeIfAbsent(descriptor, d -> {
            if (!d.startsWith("L") || !d.endsWith(";")) return false;
            var bytes = resolver.bytesOf(d.substring(1, d.length() - 1).replace('/', '.'));
            if (bytes == null) return false;
            try {
                for (var attribute : ClassFile.of().parse(bytes).attributes()) {
                    if (!(attribute instanceof RuntimeVisibleAnnotationsAttribute annotations)) continue;
                    for (var annotation : annotations.annotations()) {
                        if (NORMAL_SCOPE_DESCRIPTOR.equals(annotation.className().stringValue())) {
                            return true;
                        }
                    }
                }
            } catch (IllegalArgumentException unparseable) {
                // not class-file bytes (e.g. an encrypted entry) — not a resolvable scope
            }
            return false;
        });
    }

    /** {@code true} when the class needs the woven marker: no marker, no usable no-arg. */
    public static boolean needsMarker(byte[] bytes) {
        return !ProxyLinkWeaver.hasMarkerConstructor(bytes)
                && !ProxyLinkWeaver.hasNonPrivateNoArgConstructor(bytes);
    }

    /**
     * How the woven marker of the bean chains up: {@code NO_ARG} for {@code Object} or a
     * superclass with a usable no-arg constructor, {@code MARKER} when the superclass has
     * (or will be woven with) a marker, {@code null} when no side-effect-free chain
     * exists.
     *
     * @param candidates binary names that may themselves be woven (a superclass in this
     *                   set is analysed recursively and, if weavable, added to
     *                   {@code beans})
     * @param beans      accumulator of weaving decisions, updated for recursive
     *                   superclasses
     */
    public static ProxyLinkWeaver.SuperChain superChain(byte[] beanBytes, ByteResolver resolver,
            Set<String> candidates, Map<String, ProxyLinkWeaver.SuperChain> beans) {
        var superDesc = ClassFile.of().parse(beanBytes).superclass().orElse(null);
        if (superDesc == null) return null;
        var superName = superDesc.asInternalName().replace('/', '.');
        if ("java.lang.Object".equals(superName)) {
            return ProxyLinkWeaver.SuperChain.NO_ARG;
        }
        var superBytes = resolver.bytesOf(superName);
        if (superBytes == null) return null;
        if (ProxyLinkWeaver.hasMarkerConstructor(superBytes)) {
            return ProxyLinkWeaver.SuperChain.MARKER;
        }
        if (ProxyLinkWeaver.hasNonPrivateNoArgConstructor(superBytes)) {
            return ProxyLinkWeaver.SuperChain.NO_ARG;
        }
        if (beans.containsKey(superName)) {
            return ProxyLinkWeaver.SuperChain.MARKER;
        }
        if (candidates.contains(superName)) {
            var parentChain = superChain(superBytes, resolver, candidates, beans);
            if (parentChain != null) {
                beans.put(superName, parentChain);
                return ProxyLinkWeaver.SuperChain.MARKER;
            }
        }
        return null;
    }
}
