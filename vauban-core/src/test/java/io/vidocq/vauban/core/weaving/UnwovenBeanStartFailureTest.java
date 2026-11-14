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
package io.vidocq.vauban.core.weaving;

import io.vidocq.vauban.api.ProxyLink;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261114-01, when nothing can weave: a bean class compiled without Vauban, with a no-arg constructor, under
 * the client proxy a woven build shipped for it. The start must fail and say so, instead of starting and throwing
 * {@code NoSuchMethodError} at the first client proxy — here a REST resource would be, as nothing injects it.
 */
@DisplayName("A bean load-time weaving leaves unwoven fails the start (BUG-20261114-01)")
class UnwovenBeanStartFailureTest {

    /** Compiled by plain javac in this test module: no marker, the implicit public no-arg constructor. */
    @ApplicationScoped
    public static class BookResource {
        public String title() {
            return "Les Misérables";
        }
    }

    private static final String BEAN = BookResource.class.getName();
    private static final String PROXY = BEAN + "_ClientProxy";

    /** Lists {@link BookResource} as a bean and serves, next to its class, the proxy of a woven build. */
    private static final class ReplacedOutput extends ClassLoader {

        ReplacedOutput(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Enumeration<URL> getResources(String name) {
            if (!"META-INF/vauban-beans.list".equals(name)) {
                return Collections.emptyEnumeration();
            }
            return Collections.enumeration(List.of(inMemory(name, (BEAN + "\n").getBytes(StandardCharsets.UTF_8))));
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return (PROXY.replace('.', '/') + ".class").equals(name)
                    ? new ByteArrayInputStream(proxyChainingTheMarker())
                    : super.getResourceAsStream(name);
        }
    }

    @AfterEach
    void clearOptOut() {
        System.clearProperty(LoadTimeWeaving.MODE_PROPERTY);
    }

    @Test
    @DisplayName("with load-time weaving disabled, the start fails and names the bean and the reason")
    void startFailsOnABeanLeftUnwoven() {
        System.setProperty(LoadTimeWeaving.MODE_PROPERTY, "disabled");
        var builder = VaubanContainer.builder()
                .classLoader(new ReplacedOutput(getClass().getClassLoader()))
                .addBeanClass(BookResource.class);

        var failure = assertThrows(DeploymentException.class, () -> {
            try (var _ = builder.build()) {
                // the deployment must not succeed
            }
        });

        var message = String.valueOf(failure.getMessage());
        assertTrue(message.contains(BEAN) && message.contains(ProxyLink.CLASS_NAME)
                && message.contains("Load-time weaving is disabled"), message);
    }

    /** {@code public class BookResource_ClientProxy extends BookResource { super((ProxyLink) null); }} */
    private static byte[] proxyChainingTheMarker() {
        var bean = ClassDesc.of(BEAN);
        return ClassFile.of().build(ClassDesc.of(PROXY), clb -> clb
                .withSuperclass(bean)
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, cob -> cob
                        .aload(0)
                        .aconst_null()
                        .invokespecial(bean, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of(ProxyLink.CLASS_NAME)))
                        .return_()));
    }

    private static URL inMemory(String name, byte[] content) {
        try {
            return URL.of(URI.create("memory:/" + name), new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        @Override
                        public void connect() {
                            // in memory: nothing to connect to
                        }

                        @Override
                        public InputStream getInputStream() {
                            return new ByteArrayInputStream(content);
                        }
                    };
                }
            });
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
