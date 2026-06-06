package io.vidocq.vauban.core.provider;

import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.core.container.ProvidedBean;
import io.vidocq.vauban.indexer.codegen.Component;
import io.vidocq.vauban.indexer.codegen.FieldInject;
import io.vidocq.vauban.indexer.codegen.MethodInvoke;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the plugin's bytecode {@code _VaubanComponents} generator produces a loadable provider
 * that instantiates the listed no-arg beans in-module and returns {@code null} for the rest.
 */
@DisplayName("ComponentProviderClassGenerator (bytecode)")
class ComponentProviderClassGeneratorTest {

    @Test
    @DisplayName("the generated provider instantiates a listed bean and yields null otherwise")
    void generatesLoadableProvider() throws Exception {
        var gen = ComponentProviderClassGenerator.generate(
                "io.vidocq.vauban.core.provider._GenTestComponents",
                List.of(new Component(ProvidedBean.class.getName(), List.of())));

        var loader = new ByteClassLoader(getClass().getClassLoader());
        var providerClass = loader.define(gen.className(), gen.bytecode());
        var provider = (VaubanComponentProvider) providerClass.getDeclaredConstructor().newInstance();

        var first = provider.create(ProvidedBean.class.getName());
        assertInstanceOf(ProvidedBean.class, first, "should instantiate the listed bean in-module");
        var second = provider.create(ProvidedBean.class.getName());
        assertInstanceOf(ProvidedBean.class, second);
        assertNotSame(first, second, "each create() call must return a fresh instance");

        assertNull(provider.create("does.not.Exist"), "unlisted class must return null");

        assertTrue(providerClass.isAnnotationPresent(jakarta.enterprise.inject.Vetoed.class),
                "@Vetoed keeps Weld (bean-discovery-mode=all) from loading it as a bean");
    }

    @Test
    @DisplayName("create(String, Object[]) instantiates an injected-constructor bean in-module")
    void generatesInjectedConstructorProvider() throws Exception {
        var pkg = "io.vidocq.vauban.core.provider.test.ctor";
        var beanFqn = pkg + ".CtorTarget";
        var providerFqn = pkg + "._GenCtorComponents";

        // class CtorTarget { final String dep; public CtorTarget(String dep) { this.dep = dep; } }
        var cdBean = ClassDesc.of(beanFqn);
        var cdObject = ConstantDescs.CD_Object;
        var cdString = ConstantDescs.CD_String;
        byte[] beanBytes = ClassFile.of().build(cdBean, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(cdObject);
            clb.withField("dep", cdString, ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            clb.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, cdString),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(cdObject, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.aload(0);
                        cob.aload(1);
                        cob.putfield(cdBean, "dep", cdString);
                        cob.return_();
                    });
        });

        var component = new Component(beanFqn, List.of("java.lang.String"));
        var gen = ComponentProviderClassGenerator.generate(providerFqn, List.of(component));

        var loader = new ByteClassLoader(getClass().getClassLoader());
        var beanClass = loader.define(beanFqn, beanBytes);
        var providerClass = loader.define(gen.className(), gen.bytecode());
        var provider = (VaubanComponentProvider) providerClass.getDeclaredConstructor().newInstance();

        var instance = provider.create(beanFqn, new Object[]{"hello"});
        assertInstanceOf(beanClass, instance, "create(String, Object[]) must instantiate the bean in-module");
        var depField = beanClass.getDeclaredField("dep");
        assertEquals("hello", depField.get(instance), "the constructor argument must be passed through");

        // No/empty args must delegate to create(String); the bean has no no-arg ctor, so it yields null.
        assertNull(provider.create(beanFqn, new Object[0]),
                "empty args delegate to create(String), which does not list this injected-ctor bean");
        assertNull(provider.create("does.not.Exist", new Object[]{"x"}), "unlisted class must return null");
    }

    /**
     * Verifies injectField by generating BOTH the target bean class and the provider in the same
     * ByteClassLoader (unnamed module), so the putfield access check passes.
     *
     * <p>In production, the generated provider and the target bean share the same named module;
     * the test mirrors that by loading both in the same unnamed-module classloader.
     */
    @Test
    @DisplayName("injectField sets a package-private field via putfield and returns true")
    void injectFieldSetsPackagePrivateField() throws Exception {
        // Package shared by the synthetic target bean and the generated provider.
        var pkg = "io.vidocq.vauban.core.provider.test.inject";
        var targetFqn = pkg + ".InjectTarget";
        var providerFqn = pkg + "._GenInjectComponents";

        // Generate a minimal target bean: class InjectTarget { String service; Object dependency; }
        byte[] targetBytes = buildTargetBeanBytecode(targetFqn);

        var fi1 = new FieldInject(targetFqn, "service", "java.lang.String");
        var fi2 = new FieldInject(targetFqn, "dependency", "java.lang.Object");
        var gen = ComponentProviderClassGenerator.generate(providerFqn, List.of(), List.of(fi1, fi2));

        // Both classes loaded in the same unnamed-module ByteClassLoader — putfield access is valid.
        var loader = new ByteClassLoader(getClass().getClassLoader());
        var targetClass = loader.define(targetFqn, targetBytes);
        var providerClass = loader.define(gen.className(), gen.bytecode());
        var provider = (VaubanComponentProvider) providerClass.getDeclaredConstructor().newInstance();

        var bean = targetClass.getDeclaredConstructor().newInstance();

        assertTrue(provider.injectField(bean, targetFqn, "service", "hello"),
                "injectField must return true for a known class/field");
        var serviceField = targetClass.getDeclaredField("service");
        serviceField.setAccessible(true);
        assertEquals("hello", serviceField.get(bean), "package-private field must be set");

        var depValue = new Object();
        assertTrue(provider.injectField(bean, targetFqn, "dependency", depValue),
                "Object-typed field must also be injectable");
        var depField = targetClass.getDeclaredField("dependency");
        depField.setAccessible(true);
        assertEquals(depValue, depField.get(bean), "Object field must be set");

        assertFalse(provider.injectField(bean, "com.unknown.Class", "service", "x"),
                "unknown class must return false");
        assertFalse(provider.injectField(bean, targetFqn, "unknownField", "x"),
                "unknown field must return false");
    }

    /**
     * Generates bytecode for a minimal bean class with two package-private fields:
     * {@code String service} and {@code Object dependency}.
     */
    private static byte[] buildTargetBeanBytecode(String fqn) {
        var cd = ClassDesc.of(fqn);
        var cdObject = ConstantDescs.CD_Object;
        var cdString = ConstantDescs.CD_String;
        var mtdVoid = MethodTypeDesc.of(ConstantDescs.CD_void);
        return ClassFile.of().build(cd, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(cdObject);
            clb.withField("service", cdString, 0);       // package-private
            clb.withField("dependency", cdObject, 0);    // package-private
            clb.withMethodBody(ConstantDescs.INIT_NAME, mtdVoid, ClassFile.ACC_PUBLIC, cob -> {
                cob.aload(0);
                cob.invokespecial(cdObject, ConstantDescs.INIT_NAME, mtdVoid);
                cob.return_();
            });
        });
    }

    /**
     * Verifies {@code invoke()} dispatches to a void and a non-void in-module method, and returns
     * the {@link VaubanComponentProvider#NOT_INVOKED} sentinel for unknown class/method ids.
     *
     * <p>Both the target bean class and the generated provider are loaded in the same
     * {@link ByteClassLoader} (unnamed module), mirroring the production case where they share the
     * same named module — the invokevirtual access check passes because they are in the same package.
     */
    @Test
    @DisplayName("invoke() dispatches void and non-void methods and returns NOT_INVOKED for unknowns")
    void invokeDispatchesMethodsAndReturnsSentinelForUnknowns() throws Exception {
        var pkg = "io.vidocq.vauban.core.provider.test.invoke";
        var beanFqn = pkg + ".InvokeTarget";
        var providerFqn = pkg + "._GenInvokeComponents";

        // Build a minimal target bean:
        // class InvokeTarget {
        //   String lastArg;
        //   void record(String s) { this.lastArg = s; }
        //   Object make() { return "result"; }
        // }
        var cdBean = ClassDesc.of(beanFqn);
        var cdObject = ConstantDescs.CD_Object;
        var cdString = ConstantDescs.CD_String;
        var mtdVoid = MethodTypeDesc.of(ConstantDescs.CD_void);
        byte[] beanBytes = ClassFile.of().build(cdBean, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(cdObject);
            clb.withField("lastArg", cdString, 0); // package-private
            // default constructor
            clb.withMethodBody(ConstantDescs.INIT_NAME, mtdVoid, ClassFile.ACC_PUBLIC, cob -> {
                cob.aload(0);
                cob.invokespecial(cdObject, ConstantDescs.INIT_NAME, mtdVoid);
                cob.return_();
            });
            // void record(String s) { this.lastArg = s; }
            var mtdRecordDesc = MethodTypeDesc.of(ConstantDescs.CD_void, cdString);
            clb.withMethodBody("record", mtdRecordDesc, 0 /* package-private */, cob -> {
                cob.aload(0);
                cob.aload(1);
                cob.putfield(cdBean, "lastArg", cdString);
                cob.return_();
            });
            // Object make() { return "result"; }
            var mtdMakeDesc = MethodTypeDesc.of(cdObject);
            clb.withMethodBody("make", mtdMakeDesc, 0 /* package-private */, cob -> {
                cob.ldc("result");
                cob.areturn();
            });
        });

        var miVoid = new MethodInvoke(
                beanFqn, "record", List.of("java.lang.String"), false, true, null);
        var miNonVoid = new MethodInvoke(
                beanFqn, "make", List.of(), false, false, "java.lang.Object");

        var gen = ComponentProviderClassGenerator.generate(
                providerFqn, List.of(), List.of(), List.of(miVoid, miNonVoid));

        var loader = new ByteClassLoader(getClass().getClassLoader());
        var beanClass = loader.define(beanFqn, beanBytes);
        var providerClass = loader.define(gen.className(), gen.bytecode());
        var provider = (VaubanComponentProvider) providerClass.getDeclaredConstructor().newInstance();

        var bean = beanClass.getDeclaredConstructor().newInstance();

        // void method record(String): must return null (not NOT_INVOKED) and actually execute
        var voidResult = provider.invoke(bean, beanFqn, "record(java.lang.String)", new Object[]{"x"});
        assertNull(voidResult, "void method invoke must return null (successfully invoked)");
        var lastArgField = beanClass.getDeclaredField("lastArg");
        lastArgField.setAccessible(true);
        assertEquals("x", lastArgField.get(bean), "record() must have stored the argument");

        // non-void method make(): must return the method's return value
        var makeResult = provider.invoke(bean, beanFqn, "make()", new Object[0]);
        assertEquals("result", makeResult, "make() must return the method's return value");

        // Unknown class — must return NOT_INVOKED
        var unknownClass = provider.invoke(bean, "com.unknown.Bean", "record(java.lang.String)", new Object[]{"x"});
        assertSame(VaubanComponentProvider.NOT_INVOKED, unknownClass,
                "unknown class must return NOT_INVOKED sentinel");

        // Unknown methodId for a known class — must return NOT_INVOKED
        var unknownMethod = provider.invoke(bean, beanFqn, "nonexistent()", new Object[0]);
        assertSame(VaubanComponentProvider.NOT_INVOKED, unknownMethod,
                "unknown methodId must return NOT_INVOKED sentinel");
    }

    /** Minimal loader exposing {@code defineClass} for the generated provider bytecode. */
    private static final class ByteClassLoader extends ClassLoader {
        ByteClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
