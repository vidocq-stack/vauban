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
package io.vidocq.vauban.moduleit;

import io.vidocq.vauban.classloader.VaubanLayerFactory;
import io.vidocq.vauban.classloader.spi.PluginContext;
import io.vidocq.vauban.core.container.VaubanContainer;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleExportInfo;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Starts as a Vidocq application does, and stops: it defines an application module layer through
 * {@link VaubanLayerFactory}, loads a bean class and a service provider from it, then starts a container on a few
 * beans of this module and uses one. {@code StartLoadsFewClassesModulePathTest} runs it in a JVM of its own and
 * counts the classes that start loads (vauban#347).
 */
public final class StartProbe {

    private static final String MODULE = "com.example.probe";
    private static final String PACKAGE = "com.example.probe";

    private StartProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path archive = applicationArchive(Files.createTempDirectory("vauban-start-probe"));
        var appLayer = VaubanLayerFactory.createAppLayer(List.of(archive), ModuleLayer.boot(),
                StartProbe.class.getClassLoader(), PluginContext.empty());
        var module = appLayer.layer().findModule(MODULE).orElseThrow();
        System.out.println("layer: " + Class.forName(module, PACKAGE + ".Service").getName() + ", "
                + Class.forName(module, PACKAGE + ".ApiImpl").getName());

        try (var container = VaubanContainer.builder()
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(Collaborator.class)
                .addBeanClass(AuditedService.class)
                .addBeanClass(GenericScopedService.class)
                .addBeanClass(DollarProducers.class)
                .build()) {
            System.out.println("started: " + container.select(Collaborator.class).hello());
        }
    }

    /**
     * An exploded modular archive: a module that exports its package, an {@code @ApplicationScoped} class listed
     * in {@code META-INF/vauban-beans.list}, and a service its {@code META-INF/services} file declares.
     */
    private static Path applicationArchive(Path root) throws IOException {
        Path pkg = root.resolve(PACKAGE.replace('.', '/'));
        Files.createDirectories(pkg);
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of(MODULE), module -> module
                        .requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)
                        .exports(ModuleExportInfo.of(PackageDesc.of(PACKAGE), 0)))));
        Files.write(pkg.resolve("Service.class"), publicClass(PACKAGE + ".Service", null, true));
        Files.write(pkg.resolve("Api.class"), ClassFile.of().build(ClassDesc.of(PACKAGE + ".Api"), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)
                .withSuperclass(ConstantDescs.CD_Object)));
        Files.write(pkg.resolve("ApiImpl.class"), publicClass(PACKAGE + ".ApiImpl", PACKAGE + ".Api", false));
        Path meta = root.resolve("META-INF/services");
        Files.createDirectories(meta);
        Files.writeString(meta.resolve(PACKAGE + ".Api"), PACKAGE + ".ApiImpl\n");
        Files.writeString(root.resolve("META-INF/vauban-beans.list"), PACKAGE + ".Service\n");
        return root;
    }

    /** {@code public class <name> [implements <api>] { public <name>() {} }}, {@code @ApplicationScoped} or not. */
    private static byte[] publicClass(String name, String api, boolean applicationScoped) {
        return ClassFile.of().build(ClassDesc.of(name), type -> {
            type.withFlags(ClassFile.ACC_PUBLIC).withSuperclass(ConstantDescs.CD_Object);
            if (api != null) {
                type.withInterfaceSymbols(ClassDesc.of(api));
            }
            if (applicationScoped) {
                type.with(RuntimeVisibleAnnotationsAttribute.of(java.lang.classfile.Annotation.of(
                        ClassDesc.of("jakarta.enterprise.context.ApplicationScoped"))));
            }
            type.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC, code -> code
                            .aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void))
                            .return_());
        });
    }
}
