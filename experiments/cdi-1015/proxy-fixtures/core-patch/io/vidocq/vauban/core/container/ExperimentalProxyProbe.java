package io.vidocq.vauban.core.container;

import jakarta.enterprise.inject.se.ExperimentalPackageOpener;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Minimal bytecode experiment for CDI #1015, placed in the real provider module.
 *
 * <p>This exercises module access needed by a generated subclass. It does not
 * represent a full CDI interceptor chain or alter Vauban's production emitters.</p>
 */
public final class ExperimentalProxyProbe {
    private static final String BEAN_MODULE = "experiment.proxy.beans";
    private static final String BEAN_PACKAGE = "experiment.proxy.beans";
    private static final String BEAN_NAME = BEAN_PACKAGE + ".PackageBean";
    private static final String CLOSED_PACKAGE = "experiment.proxy.closed";
    private static final String CORE_MODULE = "io.vidocq.vauban.core";
    private static final String INITIALIZED_PROPERTY = "experiment.cdi1015.proxy.initialized";
    private static final String EXPECTED_RESULT = "intercepted:bean";

    private static final ClassDesc CD_BEAN = ClassDesc.of(BEAN_NAME);
    private static final ClassDesc CD_HELPER = ClassDesc.of(BEAN_PACKAGE + ".GeneratedModuleHelper");
    private static final ClassDesc CD_PROBE = ClassDesc.of(ExperimentalProxyProbe.class.getName());
    private static final ClassDesc CD_MARKER = ClassDesc.of(ProviderMarker.class.getName());
    private static final ClassDesc CD_MODULE = ClassDesc.of("java.lang.Module");
    private static final ClassDesc CD_LAYER = ClassDesc.of("java.lang.ModuleLayer");
    private static final ClassDesc CD_OPTIONAL = ClassDesc.of("java.util.Optional");
    private static final ClassDesc CD_LOOKUP = ClassDesc.of("java.lang.invoke.MethodHandles$Lookup");
    private static final ClassDesc CD_HANDLES = ClassDesc.of("java.lang.invoke.MethodHandles");
    private static final ClassDesc CD_SYSTEM = ClassDesc.of("java.lang.System");
    private static final MethodTypeDesc NO_ARGUMENTS = MethodTypeDesc.of(ConstantDescs.CD_void);
    private static final MethodTypeDesc STRING_RESULT = MethodTypeDesc.of(ConstantDescs.CD_String);

    private ExperimentalProxyProbe() {
    }

    /**
     * Represents a container-owned interface that must be linked before a
     * generated class can run its static initializer.
     */
    public interface ProviderMarker {
    }

    /**
     * Models the reference to container implementation code in a subclass.
     *
     * @param message the result of the bean's package-private method
     * @return a distinguishable result proving the provider call succeeded
     */
    public static String intercept(String message) {
        return "intercepted:" + message;
    }

    /**
     * Run each mode in a fresh JVM: with-reads, without-reads, or
     * interface-before-reads.
     *
     * @param arguments one mode
     * @throws Throwable if an assertion fails or an unexpected runtime error occurs
     */
    public static void main(String[] arguments) throws Throwable {
        if (arguments.length != 1
                || !(arguments[0].equals("with-reads")
                || arguments[0].equals("without-reads")
                || arguments[0].equals("interface-before-reads"))) {
            throw new IllegalArgumentException(
                    "Expected with-reads, without-reads, or interface-before-reads");
        }
        String mode = arguments[0];
        Module provider = ExperimentalProxyProbe.class.getModule();
        Module beans = ModuleLayer.boot().findModule(BEAN_MODULE).orElseThrow();
        Module cdi = ExperimentalPackageOpener.class.getModule();
        check(provider.isNamed() && provider.getName().equals(CORE_MODULE), "Real core module required");
        check(cdi.isNamed() && cdi.getName().equals("jakarta.cdi"), "Real CDI module required");
        check(!beans.getDescriptor().isAutomatic() && !beans.getDescriptor().isOpen(),
                "Bean fixture must be a closed explicit module");
        check(beans.isOpen(BEAN_PACKAGE, cdi), "Missing declarative opening to CDI");
        check(!beans.isOpen(BEAN_PACKAGE, provider), "Bean package was already open to provider");
        check(!beans.canRead(provider), "Bean module already reads provider");
        check(!beans.getDescriptor().requires().stream().anyMatch(r -> r.name().equals(CORE_MODULE)),
                "Bean descriptor must not require provider");

        MethodHandles.Lookup providerLookup = MethodHandles.lookup();
        ExperimentalPackageOpener opener = ExperimentalPackageOpener.forProvider(providerLookup);
        check(opener.providerModule() == provider, "A different provider module was selected");
        opener.openPackage(providerLookup, beans, BEAN_PACKAGE);
        check(beans.isOpen(BEAN_PACKAGE, provider), "API relay did not open package to provider");
        check(!beans.canRead(provider), "Opening unexpectedly added bean-to-provider read");

        // privateLookupIn requires the provider to read the bean module. This is
        // the opposite direction from the read edge needed by generated code.
        provider.addReads(beans);
        Class<?> beanClass = Class.forName(beans, BEAN_NAME);
        check(beanClass != null, "Bean fixture could not be loaded");
        MethodHandles.Lookup crossLookup = MethodHandles.privateLookupIn(beanClass, providerLookup);
        check((crossLookup.lookupModes() & MethodHandles.Lookup.PACKAGE) != 0,
                "Cross-module lookup lost package access");
        check(!crossLookup.hasFullPrivilegeAccess()
                        && (crossLookup.lookupModes() & MethodHandles.Lookup.MODULE) == 0,
                "Cross-module lookup unexpectedly has module access");

        // PACKAGE access is sufficient to define a regular class. Calling
        // MethodHandles.lookup() inside that class creates a fresh full lookup.
        Class<?> helperClass = crossLookup.defineClass(helperBytes());
        MethodHandles.Lookup helperLookup = (MethodHandles.Lookup) crossLookup.findStatic(
                helperClass, "lookup", MethodType.methodType(MethodHandles.Lookup.class)).invokeExact();
        check(helperClass.getModule() == beans && helperLookup.lookupClass() == helperClass,
                "Helper was not defined in the bean module");
        check(helperLookup.hasFullPrivilegeAccess(), "Generated helper did not obtain full privilege");
        check(!beans.canRead(provider), "Defining helper unexpectedly added bean-to-provider read");
        verifyUnopenedPackageAccess(beans, cdi, provider, crossLookup, helperLookup);

        if (mode.equals("interface-before-reads")) {
            check(System.getProperty(INITIALIZED_PROPERTY) == null, "Unexpected initializer marker");
            try {
                crossLookup.defineClass(subclassBytes(true));
                throw new AssertionError("Unread provider interface was accepted");
            } catch (IllegalAccessError expected) {
                checkReadFailure(expected);
                check(System.getProperty(INITIALIZED_PROPERTY) == null,
                        "Static initializer ran before interface access was checked");
                check(!beans.canRead(provider), "Failed definition unexpectedly added a read edge");
                System.out.println("PASS proxy-interface-before-reads: "
                        + "interface linkage failed before the initializer could add the read edge");
            }
            return;
        }

        if (mode.equals("with-reads")) {
            crossLookup.findStatic(helperClass, "addRead",
                    MethodType.methodType(void.class, Module.class)).invokeExact(provider);
            check(beans.canRead(provider), "Generated helper did not add bean-to-provider read");
        }

        Class<?> subclass = crossLookup.defineClass(subclassBytes(false));
        check(subclass.getModule() == beans && subclass.getSuperclass() == beanClass,
                "Subclass is not in the original bean module");
        Object instance = crossLookup.findConstructor(subclass, MethodType.methodType(void.class)).invoke();
        var invoke = crossLookup.findVirtual(beanClass, "callMessage", MethodType.methodType(String.class));
        if (mode.equals("without-reads")) {
            try {
                invoke.invoke(instance);
                throw new AssertionError("Provider method call succeeded without a bean-to-provider read");
            } catch (IllegalAccessError expected) {
                checkReadFailure(expected);
                check(!beans.canRead(provider), "Failed invocation unexpectedly added a read edge");
                System.out.println("PASS proxy-without-reads: package access and class definition succeeded; "
                        + "the call to provider code failed on the missing read edge");
            }
        } else {
            String value = (String) invoke.invoke(instance);
            check(EXPECTED_RESULT.equals(value), "Unexpected intercepted value: " + value);
            System.out.println("PASS proxy-with-reads: cross-module lookup defined a full-privilege helper; "
                    + "helper added the read edge; package-private override returned " + value);
        }
    }

    private static byte[] helperBytes() {
        return ClassFile.of().build(CD_HELPER, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            builder.withSuperclass(ConstantDescs.CD_Object);
            builder.withMethodBody("lookup", MethodTypeDesc.of(CD_LOOKUP),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                        code.invokestatic(CD_HANDLES, "lookup", MethodTypeDesc.of(CD_LOOKUP));
                        code.areturn();
                    });
            builder.withMethodBody("addRead", MethodTypeDesc.of(ConstantDescs.CD_void, CD_MODULE),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                        code.ldc(CD_HELPER);
                        code.invokevirtual(ConstantDescs.CD_Class, "getModule", MethodTypeDesc.of(CD_MODULE));
                        code.aload(0);
                        code.invokevirtual(CD_MODULE, "addReads", MethodTypeDesc.of(CD_MODULE, CD_MODULE));
                        code.pop();
                        code.return_();
                    });
        });
    }

    private static void verifyUnopenedPackageAccess(Module beans, Module cdi, Module provider,
            MethodHandles.Lookup crossLookup, MethodHandles.Lookup helperLookup) throws Throwable {
        check(!beans.isOpen(CLOSED_PACKAGE, cdi) && !beans.isOpen(CLOSED_PACKAGE, provider),
                "Sibling package must remain closed to CDI and provider");
        check(!beans.isExported(CLOSED_PACKAGE), "Sibling package must not be exported");
        Class<?> secretClass = Class.forName(beans, CLOSED_PACKAGE + ".Secret");
        check(secretClass != null, "Unopened sibling package fixture could not be loaded");
        try {
            MethodHandles.privateLookupIn(secretClass, crossLookup);
            throw new AssertionError("Cross-module lookup reached the unopened sibling package");
        } catch (IllegalAccessException expected) {
            // The original cross-module lookup lacks MODULE access.
        }
        MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(secretClass, helperLookup);
        String secret = (String) privateLookup.findStatic(
                secretClass, "secret", MethodType.methodType(String.class)).invokeExact();
        check("unopened-package-secret".equals(secret),
                "Generated helper's full lookup did not reach the private sibling-package method");
        check(!beans.isOpen(CLOSED_PACKAGE, cdi) && !beans.isOpen(CLOSED_PACKAGE, provider),
                "Lookup access must not mutate sibling package openings");
        System.out.println("OBSERVATION closed-package-access: original cross-module lookup denied; "
                + "generated helper's full lookup invoked a private method without another opening");
    }

    private static byte[] subclassBytes(boolean providerInterface) {
        ClassDesc subclass = ClassDesc.of(BEAN_PACKAGE
                + (providerInterface ? ".GeneratedInterfaceSubclass" : ".GeneratedSubclass"));
        return ClassFile.of().build(subclass, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            builder.withSuperclass(CD_BEAN);
            if (providerInterface) {
                builder.withInterfaceSymbols(CD_MARKER);
                builder.withMethodBody(ConstantDescs.CLASS_INIT_NAME, NO_ARGUMENTS,
                        ClassFile.ACC_STATIC, code -> {
                            code.ldc(INITIALIZED_PROPERTY);
                            code.ldc("ran");
                            code.invokestatic(CD_SYSTEM, "setProperty", MethodTypeDesc.of(
                                    ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String));
                            code.pop();
                            // Resolve the target Module through java.base only:
                            // this initializer could add the read edge if class
                            // linkage allowed it to run.
                            code.invokestatic(CD_LAYER, "boot", MethodTypeDesc.of(CD_LAYER));
                            code.ldc(CORE_MODULE);
                            code.invokevirtual(CD_LAYER, "findModule",
                                    MethodTypeDesc.of(CD_OPTIONAL, ConstantDescs.CD_String));
                            code.invokevirtual(CD_OPTIONAL, "orElseThrow",
                                    MethodTypeDesc.of(ConstantDescs.CD_Object));
                            code.checkcast(CD_MODULE);
                            code.invokestatic(CD_HELPER, "addRead",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_MODULE));
                            code.return_();
                        });
            }
            builder.withMethodBody(ConstantDescs.INIT_NAME, NO_ARGUMENTS, ClassFile.ACC_PUBLIC, code -> {
                code.aload(0);
                code.invokespecial(CD_BEAN, ConstantDescs.INIT_NAME, NO_ARGUMENTS);
                code.return_();
            });
            builder.withMethodBody("packageMessage", STRING_RESULT, ClassFile.ACC_PUBLIC, code -> {
                code.aload(0);
                code.invokespecial(CD_BEAN, "packageMessage", STRING_RESULT);
                code.invokestatic(CD_PROBE, "intercept",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String));
                code.areturn();
            });
        });
    }

    private static void checkReadFailure(IllegalAccessError failure) {
        check(failure.getMessage() != null
                        && failure.getMessage().contains("does not read module " + CORE_MODULE),
                "Failure was not the expected bean-to-provider read denial: " + failure);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
