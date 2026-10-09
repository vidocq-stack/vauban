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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.BeanFactory;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.spi.CreationalContext;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

/**
 * Registration of synthetic components into the container's descriptor/factory maps:
 * {@code @Synthesis} beans and observers produced at runtime by the BCE pipeline, and
 * their APT-frozen counterparts read back from
 * {@code META-INF/vauban-synthetic-metadata.properties}. Extracted from
 * {@link VaubanContainerBuilder} (which stays the facade — its {@code build()} passes
 * the mutable target collections in).
 */
final class SyntheticComponentRegistrar {

    private SyntheticComponentRegistrar() {
    }

    static void loadSyntheticMetadataFromApt(ClassLoader cl,
                                             List<BeanDescriptor> descriptors,
                                             Map<DotName, BeanFactory<?>> factories,
                                             Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers,
                                             List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers,
                                             java.util.Set<String> seenSyntheticSignatures) {
        try {
            var urls = cl.getResources(io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.METADATA_PATH);
            while (urls.hasMoreElements()) {
                loadMetadataResource(urls.nextElement(), cl, descriptors, factories, syntheticDisposers, observers,
                        seenSyntheticSignatures);
            }
        } catch (java.io.IOException _) {
            // No metadata file or read error — skip
        }
    }

    private static void loadMetadataResource(java.net.URL url, ClassLoader cl,
                                             List<BeanDescriptor> descriptors,
                                             Map<DotName, BeanFactory<?>> factories,
                                             Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers,
                                             List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers,
                                             java.util.Set<String> seenSyntheticSignatures) {
        try (var is = url.openStream()) {
            var props = new java.util.Properties();
            props.load(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));

            for (var synDesc : io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.readBeans(props)) {
                registerAptBean(synDesc, cl, descriptors, factories, syntheticDisposers, seenSyntheticSignatures);
            }
            for (var synDesc : io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer.readObservers(props)) {
                registerAptObserver(synDesc, cl, observers);
            }
        } catch (java.io.IOException _) {
            // unreadable resource — skip
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerAptBean(io.vidocq.vauban.core.extensions.SyntheticBeanDescriptor synDesc,
                                        ClassLoader cl,
                                        List<BeanDescriptor> descriptors,
                                        Map<DotName, BeanFactory<?>> factories,
                                        Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers,
                                        java.util.Set<String> seenSyntheticSignatures) {
        try {
            var beanClass = Class.forName(synDesc.beanClassName(), false, cl);
            var builder = new io.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder(beanClass);

            if (synDesc.creatorClassName() != null) {
                builder.createWith((Class) Class.forName(synDesc.creatorClassName(), false, cl));
            }
            if (synDesc.disposerClassName() != null) {
                builder.disposeWith((Class) Class.forName(synDesc.disposerClassName(), false, cl));
            }
            if (synDesc.scopeAnnotation() != null) {
                builder.scope((Class) Class.forName(synDesc.scopeAnnotation(), false, cl));
            }
            for (var typeName : synDesc.types()) {
                resolveOptionalClass(typeName, cl, builder::type);
            }
            for (var qualName : synDesc.qualifiers()) {
                resolveOptionalClass(qualName, cl, c -> builder.qualifier((Class) c));
            }
            if (synDesc.name() != null) builder.name(synDesc.name());
            builder.alternative(synDesc.alternative());
            builder.priority(synDesc.priority());

            applyParams(builder.getParams(), synDesc.params(), cl, "synthetic bean " + synDesc.beanClassName());

            registerSyntheticBean(builder, descriptors, factories, syntheticDisposers, seenSyntheticSignatures);
        } catch (ClassNotFoundException _) {
            // Synthetic bean class not found — skip
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerAptObserver(io.vidocq.vauban.core.extensions.SyntheticObserverDescriptor synDesc,
                                            ClassLoader cl,
                                            List<io.vidocq.vauban.core.bean.model.ObserverDescriptor> observers) {
        try {
            var eventClass = Class.forName(synDesc.eventTypeName(), false, cl);
            var builder = new io.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder(eventClass);
            if (synDesc.observerClassName() != null) {
                builder.observeWith(Class.forName(synDesc.observerClassName(), false, cl));
            }
            for (var qualName : synDesc.qualifiers()) {
                resolveOptionalClass(qualName, cl, c -> builder.qualifier((Class) c));
            }
            builder.priority(synDesc.priority());
            builder.async(synDesc.async());
            applyParams(builder.getParams(), synDesc.params(), cl,
                    "synthetic observer of " + synDesc.eventTypeName());

            observers.add(buildSyntheticObserver(builder));
        } catch (ClassNotFoundException _) {
            // Synthetic observer class not found — skip
        }
    }

    private static void resolveOptionalClass(String fqn, ClassLoader cl, java.util.function.Consumer<Class<?>> sink) {
        try {
            sink.accept(Class.forName(fqn, false, cl));
        } catch (ClassNotFoundException _) {
            // silently skip: classpath partial, JAR may not include this class
        }
    }

    /**
     * Every param the build wrote, read back as the type its creator or observer looks it up with. A
     * param that names a class this boot cannot load fails the deployment: dropping it would hand the
     * creator {@code null} without a word (BUG-20261008-02).
     */
    private static void applyParams(Map<String, Object> target, Map<String, String> encoded, ClassLoader cl,
                                    String owner) {
        for (var entry : encoded.entrySet()) {
            try {
                target.put(entry.getKey(),
                        io.vidocq.vauban.core.extensions.SyntheticParamCodec.decode(entry.getValue(), cl));
            } catch (IllegalArgumentException e) {
                throw new jakarta.enterprise.inject.spi.DeploymentException(
                        owner + ", param '" + entry.getKey() + "': " + e.getMessage(), e);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ObserverDescriptor buildSyntheticObserver(
            io.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder<?> synObs) {
        var eventReflectType = synObs.getEventType();
        TypeInfo eventTypeInfo;
        if (eventReflectType instanceof Class<?> cls) {
            eventTypeInfo = new TypeInfo.ClassType(DotName.of(cls.getName()));
        } else if (eventReflectType instanceof java.lang.reflect.ParameterizedType pt
                && pt.getRawType() instanceof Class<?> rawCls) {
            var typeArgs = new java.util.ArrayList<TypeInfo>();
            for (var arg : pt.getActualTypeArguments()) {
                if (arg instanceof Class<?> argCls) {
                    typeArgs.add(new TypeInfo.ClassType(DotName.of(argCls.getName())));
                } else {
                    typeArgs.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
                }
            }
            eventTypeInfo = new TypeInfo.ParameterizedType(DotName.of(rawCls.getName()), typeArgs);
        } else {
            eventTypeInfo = new TypeInfo.ClassType(DotName.of("java.lang.Object"));
        }

        // With their member values: a synthetic observer qualified @Channel("alpha") observes that
        // event and no other (BUG-20260914-15).
        var qualifiers = new java.util.ArrayList<QualifierInstance>();
        for (var q : synObs.getQualifiers()) {
            qualifiers.add(QualifierInstance.from(io.vidocq.vauban.core.annotation.AnnotationValues.infoOf(q)));
        }

        var observerClass = synObs.getObserverClass();
        var params = synObs.getParams();

        java.util.function.BiConsumer<Object, java.lang.annotation.Annotation[]> invoker = (event, eventQualifiers) -> {
            try {
                var observer = (jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver) observerClass.getDeclaredConstructor().newInstance();
                var vaubanParams = new io.vidocq.vauban.core.extensions.VaubanParameters(params);
                var metadata = new jakarta.enterprise.inject.spi.EventMetadata() {
                    @Override public java.util.Set<java.lang.annotation.Annotation> getQualifiers() {
                        var qs = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
                        if (eventQualifiers != null) {
                            for (var q : eventQualifiers) qs.add(q);
                        }
                        qs.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
                        return java.util.Set.copyOf(qs);
                    }
                    @Override public jakarta.enterprise.inject.spi.InjectionPoint getInjectionPoint() { return null; }
                    @Override public java.lang.reflect.Type getType() { return event.getClass(); }
                };
                var eventContext = new jakarta.enterprise.inject.spi.EventContext() {
                    @Override public Object getEvent() { return event; }
                    @Override public jakarta.enterprise.inject.spi.EventMetadata getMetadata() { return metadata; }
                };
                observer.observe(eventContext, vaubanParams);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new jakarta.enterprise.event.ObserverException("Synthetic observer failed", e);
            }
        };

        return new ObserverDescriptor(
                DotName.of(observerClass.getName()),
                "observe",
                eventTypeInfo,
                qualifiers,
                synObs.isAsync(),
                synObs.getPriority(),
                "ALWAYS",
                "IN_PROGRESS",
                invoker
        );
    }

    /**
     * Full identity of a synthetic bean registration: bean class, bean types,
     * qualifiers, scope, name, creator and creation parameters. Two builders with
     * the same signature register indistinguishable beans.
     */
    private static String syntheticSignature(
            io.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder<?> synBean) {
        var types = new java.util.TreeSet<String>();
        for (var type : synBean.getTypes()) {
            types.add(type.getTypeName());
        }
        for (var type : synBean.getIndexTypes()) {
            types.add(type.toString());
        }
        var qualifiers = new java.util.TreeSet<String>();
        for (var qualifier : synBean.getQualifiers()) {
            qualifiers.add(qualifier.toString());
        }
        var params = new java.util.TreeMap<String, String>();
        // deepToString: an array param would otherwise print its identity, and two identical
        // registrations would no longer collapse
        synBean.getParams().forEach((k, v) -> params.put(k, java.util.Arrays.deepToString(new Object[] {v})));
        return synBean.getBeanClass().getName()
                + "|types=" + types
                + "|qualifiers=" + qualifiers
                + "|scope=" + (synBean.getScopeAnnotation() != null ? synBean.getScopeAnnotation().getName() : "")
                + "|name=" + synBean.getName()
                + "|creator=" + (synBean.getCreatorClass() != null ? synBean.getCreatorClass().getName() : "")
                + "|params=" + params;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void registerSyntheticBean(
            io.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder<?> synBean,
            List<BeanDescriptor> descriptors,
            Map<DotName, BeanFactory<?>> factories,
            Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers,
            java.util.Set<String> seenSyntheticSignatures) {
        // VAU-BCE-002: the same synthesis logic can run more than once in a single boot
        // (a BCE visible both through the classpath scan and as an explicit bean class,
        // or re-exported as a subclass by an extension wrapper). Registering the same
        // synthetic bean twice makes every matching injection point ambiguous, so
        // identical registrations collapse to the first one.
        if (!seenSyntheticSignatures.add(syntheticSignature(synBean))) {
            return;
        }
        // Descriptor construction is shared with the APT pipeline so the deployment validator
        // sees the same view of synthetic beans at compile time and at runtime.
        var descriptor = io.vidocq.vauban.core.extensions.BceProcessor
                .toBeanDescriptor(synBean, descriptors.size());
        descriptors.add(descriptor);
        var syntheticKey = DotName.of(descriptor.id().value());

        // Create factory using SyntheticBeanCreator
        var creatorClass = synBean.getCreatorClass();
        var creatorParams = synBean.getParams();
        var isDependent = descriptor.scope().equals(io.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT);
        factories.put(syntheticKey, new BeanFactory<Object>() {
            @Override
            public Object create() { return create((CreationalContext<Object>) null); }
            @Override
            public Object create(CreationalContext<Object> ctx) {
                try {
                    @SuppressWarnings("unchecked")
                    var creator = (jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator<Object>)
                            creatorClass.getDeclaredConstructor().newInstance();
                    var vaubanParams = new io.vidocq.vauban.core.extensions.VaubanParameters(creatorParams);
                    var container = VaubanContainer.current();
                    ScopedValue.CallableOp<Object, Exception> createAction = () -> {
                        var parentCtx = ctx instanceof io.vidocq.vauban.core.context.CreationalContextImpl<?> cci ? cci : null;
                        var lookup = new InstanceImpl<>(container, Object.class, new Annotation[0], null, parentCtx);
                        return creator.create(lookup, vaubanParams);
                    };
                    if (VaubanContainer.getCurrentInjectionPoint() == null && isDependent) {
                        return VaubanContainer.callWithInjectionPoint(VaubanInjectionPoint.EMPTY, createAction);
                    }
                    return createAction.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new jakarta.enterprise.inject.CreationException(e);
                }
            }
        });

        // Register synthetic disposer if present
        var disposerClass = synBean.getDisposerClass();
        if (disposerClass != null) {
            syntheticDisposers.put(syntheticKey, (instance, ctx) -> {
                try {
                    @SuppressWarnings("unchecked")
                    var disposer = (jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer<Object>)
                            disposerClass.getDeclaredConstructor().newInstance();
                    var vaubanParams = new io.vidocq.vauban.core.extensions.VaubanParameters(creatorParams);
                    var container = VaubanContainer.current();
                    var lookup = new InstanceImpl<>(container, Object.class, new Annotation[]{jakarta.enterprise.inject.Default.Literal.INSTANCE}, null, (io.vidocq.vauban.core.context.CreationalContextImpl<?>) ctx);
                    disposer.dispose(instance, lookup, vaubanParams);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    // CDI spec: exceptions in disposer methods are suppressed
                }
            });
        }
    }
}
