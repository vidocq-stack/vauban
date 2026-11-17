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
import io.vidocq.vauban.core.bean.model.BeanId;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.processor.apt.ProcessorCompiler;
import io.vidocq.vauban.processor.codegen.provider.ComponentProviderGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build-time client proxy of a normal-scoped producer is the one the container looks up, or none, the container
 * then generating it at run time (BUG-20261117-15): none when the interface the container proxies is not public, and
 * none when the producer's member is not found, as the descriptor's bean types, a set in no order, do not say which
 * type the container proxies.
 */
class ProducerProxyChoiceTest {

    private static final String FACTORY = "vi.p.Factory";
    private static final Map<String, String> SOURCES = Map.of(
            "vi/p/Shown.java", "package vi.p;\n\npublic interface Shown {\n}\n",
            "vi/p/Hidden.java", "package vi.p;\n\ninterface Hidden extends Shown {\n}\n",
            "vi/p/Factory.java", """
                    package vi.p;

                    public class Factory {
                        @jakarta.enterprise.inject.Produces
                        Shown shown() {
                            return null;
                        }

                        @jakarta.enterprise.inject.Produces
                        Hidden hidden() {
                            return null;
                        }
                    }
                    """);
    private static final TypeInfo SHOWN = new TypeInfo.ClassType(DotName.of("vi.p.Shown"));
    private static final TypeInfo OBJECT = new TypeInfo.ClassType(DotName.of("java.lang.Object"));

    @TempDir
    Path workDir;

    @Test
    @DisplayName("a producer of a public interface gets the proxy of that interface")
    void aProducerOfAPublicInterfaceGetsTheProxyOfThatInterface() throws IOException {
        assertEquals(List.of("vi.p.Shown_ClientProxy"), proxiesOf(producer("shown", SHOWN, OBJECT)));
    }

    @Test
    @DisplayName("a producer of an interface that is not public gets no proxy, not that of a public one above it")
    void aProducerOfAnInterfaceThatIsNotPublicGetsNoProxy() throws IOException {
        var hidden = new TypeInfo.ClassType(DotName.of("vi.p.Hidden"));

        assertEquals(List.of(), proxiesOf(producer("hidden", hidden, SHOWN, OBJECT)));
    }

    @Test
    @DisplayName("a producer whose member is not found gets no proxy, though one of its bean types is public")
    void aProducerWhoseMemberIsNotFoundGetsNoProxy() throws IOException {
        assertEquals(List.of(), proxiesOf(producer("missing", SHOWN, OBJECT)));
    }

    /** The application-scoped producer method {@code method} of {@code vi.p.Factory}, of bean types {@code types}. */
    private static BeanDescriptor producer(String method, TypeInfo... types) {
        return new BeanDescriptor(BeanId.ofProducerMethod(DotName.of(FACTORY), method), DotName.of(FACTORY),
                BeanDescriptor.BeanKind.PRODUCER_METHOD, Set.of(types),
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY), ScopeInfo.APPLICATION, false, 0, List.of(),
                null);
    }

    /** The keys of the proxies the processor generates for {@code producer}, compiling {@link #SOURCES}. */
    private List<String> proxiesOf(BeanDescriptor producer) throws IOException {
        var choice = new ProxyChoice(producer);

        var result = ProcessorCompiler.compile(workDir, SOURCES, List.of(), choice);

        assertTrue(result.success(), result.messages()::toString);
        return choice.keys;
    }

    /** Hands {@code producer} to {@link ClientProxyArtefacts#generateForProducer} once, and keeps the proxies' keys. */
    private static final class ProxyChoice extends AbstractProcessor {

        private final BeanDescriptor producer;
        private final List<String> keys = new ArrayList<>();
        private boolean done;

        ProxyChoice(BeanDescriptor producer) {
            this.producer = producer;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (!done) {
                done = true;
                var generation = new Generation();
                new ClientProxyArtefacts(processingEnv, new GeneratedOutput(processingEnv))
                        .generateForProducer(producer, generation);
                generation.producerProxiesByPackage.values().forEach(proxies -> proxies.stream()
                        .map(ComponentProviderGenerator.ProducerProxy::key).forEach(keys::add));
            }
            return false;
        }
    }
}
