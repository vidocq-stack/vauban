/*
 * Copyright 2016, Red Hat, Inc., and individual contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jakarta.enterprise.inject.se;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import jakarta.enterprise.inject.spi.Extension;

/**
 * A CDI container initializer for Java SE. An instance may be obtained by calling {@link SeContainerInitializer#newInstance()}
 * static method.
 * <p>
 * Typical usage looks like this:
 * </p>
 *
 * <pre>
 * SeContainer container = SeContainerInitializer.newInstance().initialize();
 * container.select(Foo.class).get();
 * container.close();
 * </pre>
 *
 * <p>
 * Since {@link SeContainer} interface implements AutoCloseable:
 * </p>
 *
 * <pre>
 * try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
 *     container.select(Foo.class).get();
 * }
 * </pre>
 *
 * <p>
 * By default, the discovery is enabled so that all beans from all discovered bean archives are considered. However, it's
 * possible to define a "synthetic" bean archive, or the set of bean classes and enablement respectively:
 * </p>
 *
 * <pre>
 * SeContainer container = SeContainerInitializer.newInstance().addBeanClasses(Foo.class, Bar.class).selectAlternatives(Bar.class).initialize());
 * </pre>
 *
 * <p>
 * Moreover, it's also possible to disable the discovery completely so that only the "synthetic" bean archive is considered:
 * </p>
 *
 * <pre>
 * SeContainer container = SeContainerInitializer.newInstance().disableDiscovery().addBeanClasses(Foo.class, Bar.class).initialize());
 * </pre>
 *
 * <p>
 * In the same manner, it is possible to explicitly declare interceptors, decorators, extensions and implementation specific
 * options using the builder.
 * </p>
 *
 * <pre>
 * SeContainerInitializer containerInitializer = SeContainerInitializer.newInstance()
 *         .disableDiscovery()
 *         .addPackages(Main.class, Utils.class)
 *         .enableInterceptors(TransactionalInterceptor.class)
 *         .addProperty("property", true);
 * SeContainer container = container.initialize();
 * </pre>
 *
 * <p>
 * CDI Lite implementations are not required to provide support for CDI in Java SE.
 * </p>
 *
 * @author Antoine Sabot-Durand
 * @author Martin Kouba
 * @author John D. Ament
 * @since 2.0
 */
public abstract class SeContainerInitializer {

    // Experimental CDI #1015 proposal, added to the released CDI 4.1.0 source.
    private ExperimentalPackageOpener experimentalPackageOpener;

    /**
     * Returns an instance of {@link SeContainerInitializer}
     * Each call returns a new instance
     *
     * @return a new SeContainerInitializer instance.
     * @throws IllegalStateException if called in a Java EE container
     */
    public static SeContainerInitializer newInstance() {
        SeContainerInitializer initializer = findSeContainerInitializer();
        initializer.experimentalPackageOpener = new ExperimentalPackageOpener(initializer);
        return initializer;
    }

    private static SeContainerInitializer findSeContainerInitializer() {

        SeContainerInitializer result;
        Iterator<SeContainerInitializer> iterator = ServiceLoader
                .load(SeContainerInitializer.class, SeContainerInitializer.class.getClassLoader()).iterator();

        if (!iterator.hasNext()) {
            throw new IllegalStateException("No valid CDI implementation found");
        }
        try {
            result = iterator.next();
        } catch (ServiceConfigurationError e) {
            throw new IllegalStateException("Error while instantiating SeContainerInitializer", e);
        }
        if (iterator.hasNext())
            throw new IllegalStateException("Two or more CDI implementations found, only one is supported");
        return result;
    }

    /**
     * Experimental CDI #1015 proposal: authorizes an additional module to receive
     * openings for all bean packages that this initializer's container processes
     * and that are open to {@code jakarta.cdi}.
     *
     * <p>The provider performs the relays during initialization. This method
     * records consent without opening packages immediately.</p>
     *
     * @param recipient the actual module authorized to receive openings
     * @return self
     * @throws NullPointerException if recipient is null
     * @throws IllegalStateException if this object was not obtained through
     *         {@link #newInstance()} or its access policy has already been sealed
     */
    public final SeContainerInitializer allowAccessFor(Module recipient) {
        requirePackageOpener().authorize(recipient);
        return this;
    }

    /**
     * Experimental CDI #1015 proposal: authorizes an additional module only for
     * the named packages of the given source module. Subpackages are not included.
     * Repeated calls accumulate grants.
     *
     * <p>Package existence is checked now; the source must also be open to
     * {@code jakarta.cdi} when the provider requests the relay.</p>
     *
     * @param recipient the actual module authorized to receive openings
     * @param source the actual module containing the packages
     * @param packages one or more exact package names
     * @return self
     * @throws NullPointerException if an argument or package name is null
     * @throws IllegalArgumentException if no packages are supplied or a package
     *         is empty or absent from source
     * @throws IllegalStateException if this object was not obtained through
     *         {@link #newInstance()} or its access policy has already been sealed
     */
    public final SeContainerInitializer allowAccessFor(Module recipient, Module source, String... packages) {
        requirePackageOpener().authorize(recipient, source, packages);
        return this;
    }

    /**
     * Experimental provider hook: seals the application access policy and returns
     * the opening relay bound to this selected initializer.
     *
     * <p>Call at the start of {@link #initialize()}. Further calls return the same
     * sealed relay; later {@code allowAccessFor} calls are rejected. Module
     * openings remain effective after shutdown and are shared by containers
     * using the same module objects.</p>
     *
     * @return the sealed relay for this initializer
     * @throws IllegalStateException if this object was not obtained through
     *         {@link #newInstance()}
     */
    protected final ExperimentalPackageOpener packageOpener() {
        ExperimentalPackageOpener opener = requirePackageOpener();
        opener.seal(this);
        return opener;
    }

    private ExperimentalPackageOpener requirePackageOpener() {
        if (experimentalPackageOpener == null) {
            throw new IllegalStateException(
                    "Package access requires an initializer obtained from SeContainerInitializer.newInstance()");
        }
        return experimentalPackageOpener;
    }

    /**
     * Add provided bean classes to the synthetic bean archive.
     *
     * @param classes classes to add to the synthetic bean archive
     * @return self
     */
    public abstract SeContainerInitializer addBeanClasses(Class<?>... classes);

    /**
     * All classes from the packages of the specified classes will be added to the set of bean classes for the synthetic bean
     * archive.
     * <p>
     * Note that the scanning possibilities are limited. Therefore, only directories and jar files from the filesystem are
     * supported.
     * </p>
     * <p>
     * Scanning may also have negative impact on SE performance.
     * </p>
     *
     * @param packageClasses classes whose packages will be added to the synthetic bean archive
     * @return self
     */
    public abstract SeContainerInitializer addPackages(Class<?>... packageClasses);

    /**
     * Packages of the specified classes will be scanned and found classes will be added to the set of bean classes for the
     * synthetic bean archive.*
     * <p>
     * Note that the scanning possibilities are limited. Therefore, only directories and jar files from the filesystem are
     * supported.
     * </p>
     * <p>
     * Scanning may also have negative impact on SE performance.
     * </p>
     *
     * @param scanRecursively should subpackages be scanned or not
     * @param packageClasses classes whose packages will be scanned
     * @return self
     */
    public abstract SeContainerInitializer addPackages(boolean scanRecursively, Class<?>... packageClasses);

    /**
     * All classes from the specified packages will be added to the set of bean classes for the synthetic bean archive.
     * <p>
     * Note that the scanning possibilities are limited. Therefore, only directories and jar files from the filesystem are
     * supported.
     * </p>
     * <p>
     * Scanning may also have negative impact on SE performance.
     * </p>
     *
     * @param packages packages that will be added to the synthetic bean archive
     * @return self
     */
    public abstract SeContainerInitializer addPackages(Package... packages);

    /**
     * All classes from the specified packages will be added to the set of bean classes for the synthetic bean archive.
     * <p>
     * Note that the scanning possibilities are limited. Therefore, only directories and jar files from the filesystem are
     * supported.
     * </p>
     * <p>
     * Scanning may also have negative impact on SE performance.
     * </p>
     *
     * @param scanRecursively should subpackages be scanned or not
     * @param packages packages that will be added to the synthetic bean archive
     * @return self
     */
    public abstract SeContainerInitializer addPackages(boolean scanRecursively, Package... packages);

    /**
     * Add extensions to the set of extensions.
     *
     * @param extensions extensions to use in the container
     * @return self
     */
    public abstract SeContainerInitializer addExtensions(Extension... extensions);

    /**
     * Add extensions to the set of extensions.
     *
     * @param extensions extensions class to use in the container
     * @return self
     */
    @SuppressWarnings("unchecked")
    public abstract SeContainerInitializer addExtensions(Class<? extends Extension>... extensions);

    /**
     * Add interceptor classes to the list of enabled interceptors for the synthetic bean archive.
     * <p>
     * This method does not add any class to the set of bean classes of the synthetic bean archive.
     * </p>
     *
     * @param interceptorClasses classes of the interceptors to enable.
     * @return self
     */
    public abstract SeContainerInitializer enableInterceptors(Class<?>... interceptorClasses);

    /**
     * Add decorator classes to the list of enabled decorators for the synthetic bean archive.
     * <p>
     * This method does not add any class to the set of bean classes of the synthetic bean archive.
     * </p>
     *
     * @param decoratorClasses classes of the decorators to enable.
     * @return self
     */
    public abstract SeContainerInitializer enableDecorators(Class<?>... decoratorClasses);

    /**
     * Add alternatives classes to the list of selected alternatives for the synthetic bean archive.
     * <p>
     * This method does not add any class to the set of bean classes of the synthetic bean archive.
     * </p>
     *
     * @param alternativeClasses classes of the alternatives to select
     * @return self
     */
    public abstract SeContainerInitializer selectAlternatives(Class<?>... alternativeClasses);

    /**
     * Add alternative stereotype classes to the list of selected alternative stereotypes for the synthetic bean archive.
     * <p>
     * This method does not add any class to the set of bean classes of the synthetic bean archive.
     * </p>
     *
     * @param alternativeStereotypeClasses alternatives stereotypes to select
     * @return self
     */
    @SuppressWarnings("unchecked")
    public abstract SeContainerInitializer selectAlternativeStereotypes(
            Class<? extends Annotation>... alternativeStereotypeClasses);

    /**
     * Add a configuration property to the container
     *
     * @param key property name
     * @param value property value
     * @return self
     */
    public abstract SeContainerInitializer addProperty(String key, Object value);

    /**
     * Set all the configuration properties.
     * Erase previous properties set
     *
     * @param properties a map containing properties to add
     * @return self
     */
    public abstract SeContainerInitializer setProperties(Map<String, Object> properties);

    /**
     * By default, the discovery is enabled. However, it's possible to disable the discovery completely so that only the
     * "synthetic" bean archive is considered.
     *
     * @return self
     */
    public abstract SeContainerInitializer disableDiscovery();

    /**
     * Set a {@link ClassLoader}. The given {@link ClassLoader} will be scanned automatically for bean archives if scanning is
     * enabled.
     *
     * @param classLoader the class loader to use
     * @return self
     */
    public abstract SeContainerInitializer setClassLoader(ClassLoader classLoader);

    /**
     * <p>
     * Initializes a CDI SeContainerInitializer.
     * </p>
     * <p>
     * Cannot be called within an application server.
     * </p>
     *
     * @return the {@link SeContainer} instance associated with the container.
     * @throws UnsupportedOperationException if called within an application server
     */
    public abstract SeContainer initialize();

}
