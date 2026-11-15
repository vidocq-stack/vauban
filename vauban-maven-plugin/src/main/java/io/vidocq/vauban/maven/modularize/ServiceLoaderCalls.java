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
package io.vidocq.vauban.maven.modularize;

import io.vidocq.vauban.maven.module.ModuleDescriptorSynthesizer;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Declares, at the call, the service a {@code ServiceLoader} lookup of a patched jar asks for (Vidocq/vidocq#598).
 *
 * <p>An explicit module may only load the services its descriptor declares in {@code uses}. Some libraries load a type
 * their callers give them: Vaadin's {@code Lookup} loads the application's route classes. No descriptor can declare
 * those: the type is in a module that depends on the library, and a {@code uses} of it would need a {@code requires}
 * back. {@link UsesLegality} drops such directives, and every lookup then fails with "does not declare `uses`".</p>
 *
 * <p>{@code Module.addUses} declares a service at run time, but only from the module itself. So each
 * {@code ServiceLoader.load} or {@code loadInstalled} method reference of a patched class is redirected, in its
 * constant pool, to a generated class of the same package, {@value #BRIDGE}, whose method of the same name and type
 * calls {@code addUses} on its own module and then the original method. The code of the class is left as it is, so
 * its stack maps stay valid without reading the hierarchy of its types. For a service the descriptor declares, {@code addUses} does nothing. The lookup still
 * needs the service type to be accessible: exported or opened to the library, as a Vaadin route must be.</p>
 *
 * <p>A method reference ({@code ServiceLoader::load}) goes through the same constant, so it is redirected too; a
 * reflective call is not.</p>
 */
final class ServiceLoaderCalls implements ModuleDescriptorSynthesizer.EntryRewriter {

    /** The simple name of the generated class, one per package that calls {@code ServiceLoader}. */
    static final String BRIDGE = "VaubanServiceLoader";

    private static final String SERVICE_LOADER = "java/util/ServiceLoader";
    private static final ClassDesc CD_SERVICE_LOADER = ClassDesc.ofInternalName(SERVICE_LOADER);
    private static final ClassDesc CD_MODULE = ClassDesc.of("java.lang.Module");
    private static final Set<String> LOOKUPS = Set.of("load", "loadInstalled");
    private static final String CLASS_SUFFIX = ".class";
    private static final String VERSIONS = "META-INF/versions/";
    /** The class file version that first allows a class constant ({@code ldc}). */
    private static final int MIN_MAJOR = ClassFile.JAVA_5_VERSION;

    /** Per package (internal name): the bridge methods its calls need, by name and type, and the class version. */
    private final Map<String, Map<String, MethodTypeDesc>> bridges = new TreeMap<>();
    private final Map<String, Integer> majors = new TreeMap<>();

    @Override
    public byte[] rewrite(String name, byte[] bytes) {
        if (!name.endsWith(CLASS_SUFFIX) || name.endsWith("module-info.class")
                || (name.startsWith("META-INF/") && !name.startsWith(VERSIONS))) {
            return bytes;
        }
        ClassModel model = ClassFile.of().parse(bytes);
        List<MethodRefEntry> lookups = new ArrayList<>();
        for (var entry : model.constantPool()) {
            if (entry instanceof MethodRefEntry ref && isLookup(ref)) {
                lookups.add(ref);
            }
        }
        String internalName = model.thisClass().asInternalName();
        int slash = internalName.lastIndexOf('/');
        if (lookups.isEmpty() || slash < 0) {
            return bytes; // nothing to declare, or the unnamed package, which is in no module
        }
        String pkg = internalName.substring(0, slash);
        Map<String, MethodTypeDesc> methods = bridges.computeIfAbsent(pkg, _ -> new LinkedHashMap<>());
        majors.merge(pkg, Math.max(MIN_MAJOR, model.majorVersion()), Math::max);
        Set<Integer> indexes = new TreeSet<>();
        for (MethodRefEntry ref : lookups) {
            methods.putIfAbsent(ref.name().stringValue() + ref.type().stringValue(), ref.typeSymbol());
            indexes.add(ref.index());
        }
        return ConstantPoolPatch.redirectOwner(bytes, indexes, pkg + "/" + BRIDGE);
    }

    /** One bridge class per package whose classes call {@code ServiceLoader}, at the root of the jar. */
    @Override
    public Map<String, byte[]> added() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        bridges.forEach((pkg, methods) -> classes.put(pkg + "/" + BRIDGE + CLASS_SUFFIX,
                bridgeClass(ClassDesc.ofInternalName(pkg + "/" + BRIDGE), majors.get(pkg), methods)));
        return classes;
    }

    /** How many packages had their calls rewritten. */
    int packages() {
        return bridges.size();
    }

    private static boolean isLookup(MethodRefEntry ref) {
        return SERVICE_LOADER.equals(ref.owner().asInternalName()) && LOOKUPS.contains(ref.name().stringValue())
                && classParameter(ref.typeSymbol()) >= 0;
    }

    /** The slot of the first {@code Class} parameter of a static method, or -1 when there is none. */
    private static int classParameter(MethodTypeDesc type) {
        for (int i = 0; i < type.parameterCount(); i++) {
            if (ConstantDescs.CD_Class.equals(type.parameterType(i))) {
                return i; // every parameter of a ServiceLoader lookup is a reference: one slot each
            }
        }
        return -1;
    }

    /**
     * {@code static ServiceLoader <name>(<parameters>) { Bridge.class.getModule().addUses(type); return
     * ServiceLoader.<name>(<parameters>); }} for each method.
     */
    private static byte[] bridgeClass(ClassDesc bridge, int major, Map<String, MethodTypeDesc> methods) {
        return ClassFile.of().build(bridge, clb -> {
            clb.withVersion(major, 0);
            clb.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_SYNTHETIC);
            clb.withSuperclass(ConstantDescs.CD_Object);
            methods.forEach((key, type) -> {
                String name = key.substring(0, key.indexOf('('));
                clb.withMethodBody(name, type, ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, cob -> {
                    cob.ldc(bridge);
                    cob.invokevirtual(ConstantDescs.CD_Class, "getModule", MethodTypeDesc.of(CD_MODULE));
                    cob.aload(classParameter(type));
                    cob.invokevirtual(CD_MODULE, "addUses", MethodTypeDesc.of(CD_MODULE, ConstantDescs.CD_Class));
                    cob.pop();
                    for (int slot = 0; slot < type.parameterCount(); slot++) {
                        cob.aload(slot);
                    }
                    cob.invokestatic(CD_SERVICE_LOADER, name, type);
                    cob.areturn();
                });
            });
        });
    }
}
