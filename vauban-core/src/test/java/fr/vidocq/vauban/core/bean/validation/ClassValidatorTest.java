package fr.vidocq.vauban.core.bean.validation;

import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.*;
import fr.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClassValidator - CDI class-level validation rules")
class ClassValidatorTest {

    private static final DotName APP_SCOPED = DotName.of("jakarta.enterprise.context.ApplicationScoped");
    private static final DotName REQUEST_SCOPED = DotName.of("jakarta.enterprise.context.RequestScoped");
    private static final DotName INJECT = DotName.of("jakarta.inject.Inject");
    private static final DotName PRODUCES = DotName.of("jakarta.enterprise.inject.Produces");
    private static final DotName OBSERVES = DotName.of("jakarta.enterprise.event.Observes");
    private static final DotName OBSERVES_ASYNC = DotName.of("jakarta.enterprise.event.ObservesAsync");
    private static final DotName DISPOSES = DotName.of("jakarta.enterprise.inject.Disposes");

    private static AnnotationInfo ann(DotName name) {
        return new AnnotationInfo(name, Map.of());
    }

    private VaubanIndex buildIndex(ClassInfo... classes) {
        var builder = new IndexBuilder();
        for (var c : classes) {
            builder.add(c);
        }
        return builder.build();
    }

    @Nested
    @DisplayName("Rule 1: Multiple @Inject constructors")
    class MultipleInjectConstructors {

        @Test
        @DisplayName("should reject bean with two @Inject constructors")
        void rejectsMultipleInjectCtors() {
            var ctor1 = new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(
                    new ParameterInfo("a", new TypeInfo.ClassType(DotName.of("java.lang.String")), List.of())
            ), List.of(), 0x0001, List.of(ann(INJECT)));
            var ctor2 = new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(
                    new ParameterInfo("b", new TypeInfo.ClassType(DotName.of("java.lang.Integer")), List.of())
            ), List.of(), 0x0001, List.of(ann(INJECT)));

            var classInfo = new ClassInfo(DotName.of("com.example.Bad"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(ctor1, ctor2), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertEquals(1, errors.size());
            assertTrue(errors.getFirst().contains("multiple @Inject constructors"));
        }

        @Test
        @DisplayName("should accept bean with one @Inject constructor")
        void acceptsSingleInjectCtor() {
            var ctor = new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(
                    new ParameterInfo("a", new TypeInfo.ClassType(DotName.of("java.lang.String")), List.of())
            ), List.of(), 0x0001, List.of(ann(INJECT)));

            var classInfo = new ClassInfo(DotName.of("com.example.Good"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(ctor), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.isEmpty());
        }
    }

    @Nested
    @DisplayName("Rule 2: @Observes + @Produces on same method")
    class ObservesPlusProduces {

        @Test
        @DisplayName("should reject observer method that is also a producer")
        void rejectsObserverProducer() {
            var method = new MethodInfo("observe", new TypeInfo.ClassType(DotName.of("java.lang.String")),
                    List.of(new ParameterInfo("event", new TypeInfo.ClassType(DotName.of("java.lang.Object")),
                            List.of(ann(OBSERVES)))),
                    List.of(), 0x0001, List.of(ann(PRODUCES)));

            var classInfo = new ClassInfo(DotName.of("com.example.Bad"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(method), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.stream().anyMatch(e -> e.contains("observer") && e.contains("producer")));
        }
    }

    @Nested
    @DisplayName("Rule 3: @Observes + @Inject on same parameter")
    class ObservesPlusInjectParam {

        @Test
        @DisplayName("should reject parameter with both @Observes and @Inject")
        void rejectsObservesInjectParam() {
            var method = new MethodInfo("observe", new TypeInfo.VoidType(),
                    List.of(new ParameterInfo("event", new TypeInfo.ClassType(DotName.of("java.lang.Object")),
                            List.of(ann(OBSERVES), ann(INJECT)))),
                    List.of(), 0x0001, List.of());

            var classInfo = new ClassInfo(DotName.of("com.example.Bad"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(method), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.stream().anyMatch(e -> e.contains("@Observes") && e.contains("@Inject")));
        }
    }

    @Nested
    @DisplayName("Rule 4: @Observes + @Disposes on same parameter")
    class ObservesPlusDisposesParam {

        @Test
        @DisplayName("should reject parameter with both @Observes and @Disposes")
        void rejectsObservesDisposesParam() {
            var method = new MethodInfo("observe", new TypeInfo.VoidType(),
                    List.of(new ParameterInfo("event", new TypeInfo.ClassType(DotName.of("java.lang.Object")),
                            List.of(ann(OBSERVES), ann(DISPOSES)))),
                    List.of(), 0x0001, List.of());

            var classInfo = new ClassInfo(DotName.of("com.example.Bad"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(method), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.stream().anyMatch(e -> e.contains("@Observes") && e.contains("@Disposes")));
        }
    }

    @Nested
    @DisplayName("Rule 5: Multiple @Observes parameters in same method")
    class MultipleObservesParams {

        @Test
        @DisplayName("should reject method with two @Observes parameters")
        void rejectsMultipleObserves() {
            var method = new MethodInfo("observe", new TypeInfo.VoidType(),
                    List.of(
                            new ParameterInfo("a", new TypeInfo.ClassType(DotName.of("java.lang.Object")),
                                    List.of(ann(OBSERVES))),
                            new ParameterInfo("b", new TypeInfo.ClassType(DotName.of("java.lang.String")),
                                    List.of(ann(OBSERVES)))
                    ), List.of(), 0x0001, List.of());

            var classInfo = new ClassInfo(DotName.of("com.example.Bad"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(method), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.stream().anyMatch(e -> e.contains("multiple observer parameters")));
        }
    }

    @Nested
    @DisplayName("Rule 6: @Observes + @ObservesAsync in same method")
    class ObservesAndObservesAsync {

        @Test
        @DisplayName("should reject method with both @Observes and @ObservesAsync")
        void rejectsMixed() {
            var method = new MethodInfo("observe", new TypeInfo.VoidType(),
                    List.of(
                            new ParameterInfo("a", new TypeInfo.ClassType(DotName.of("java.lang.Object")),
                                    List.of(ann(OBSERVES))),
                            new ParameterInfo("b", new TypeInfo.ClassType(DotName.of("java.lang.String")),
                                    List.of(ann(OBSERVES_ASYNC)))
                    ), List.of(), 0x0001, List.of());

            var classInfo = new ClassInfo(DotName.of("com.example.Bad"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(method), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.stream().anyMatch(e -> e.contains("@Observes") && e.contains("@ObservesAsync")));
        }
    }

    @Nested
    @DisplayName("Rule 7: Constructor with @Observes or @Disposes")
    class ConstructorObservesDisposes {

        @Test
        @DisplayName("should reject constructor with @Observes parameter")
        void rejectsCtorObserves() {
            var ctor = new MethodInfo("<init>", new TypeInfo.VoidType(),
                    List.of(new ParameterInfo("event", new TypeInfo.ClassType(DotName.of("java.lang.Object")),
                            List.of(ann(OBSERVES)))),
                    List.of(), 0x0001, List.of());

            var classInfo = new ClassInfo(DotName.of("com.example.Bad"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(ctor), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.stream().anyMatch(e -> e.contains("Constructor") && e.contains("@Observes")));
        }

        @Test
        @DisplayName("should reject constructor with @Disposes parameter")
        void rejectsCtorDisposes() {
            var ctor = new MethodInfo("<init>", new TypeInfo.VoidType(),
                    List.of(new ParameterInfo("resource", new TypeInfo.ClassType(DotName.of("java.lang.Object")),
                            List.of(ann(DISPOSES)))),
                    List.of(), 0x0001, List.of());

            var classInfo = new ClassInfo(DotName.of("com.example.Bad"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(ctor), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.stream().anyMatch(e -> e.contains("Constructor") && e.contains("@Disposes")));
        }
    }

    @Nested
    @DisplayName("Rule 8: @Produces + @Inject on same field")
    class ProducesInjectField {

        @Test
        @DisplayName("should reject field with both @Produces and @Inject")
        void rejectsProducesInjectField() {
            var field = new FieldInfo("myField", new TypeInfo.ClassType(DotName.of("java.lang.String")),
                    0x0001, List.of(ann(PRODUCES), ann(INJECT)));

            var classInfo = new ClassInfo(DotName.of("com.example.Bad"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(field), List.of(), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.stream().anyMatch(e -> e.contains("@Produces") && e.contains("@Inject")));
        }
    }

    @Nested
    @DisplayName("Rule 9: @Observes on @Disposes method")
    class ObservesOnDisposesMethod {

        @Test
        @DisplayName("should reject method that is both observer and disposer")
        void rejectsObserverDisposer() {
            var method = new MethodInfo("dispose", new TypeInfo.VoidType(),
                    List.of(
                            new ParameterInfo("event", new TypeInfo.ClassType(DotName.of("java.lang.Object")),
                                    List.of(ann(OBSERVES))),
                            new ParameterInfo("resource", new TypeInfo.ClassType(DotName.of("java.lang.String")),
                                    List.of(ann(DISPOSES)))
                    ), List.of(), 0x0001, List.of());

            var classInfo = new ClassInfo(DotName.of("com.example.Bad"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(method), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.stream().anyMatch(e -> e.contains("observer") && e.contains("disposer")));
        }
    }

    @Nested
    @DisplayName("Rule 10: Multiple scope annotations")
    class MultipleScopes {

        @Test
        @DisplayName("should reject bean with two scope annotations")
        void rejectsMultipleScopes() {
            var classInfo = new ClassInfo(DotName.of("com.example.MultiScope"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(),
                    List.of(ann(APP_SCOPED), ann(REQUEST_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.stream().anyMatch(e -> e.contains("multiple scope")));
        }

        @Test
        @DisplayName("should accept bean with single scope annotation")
        void acceptsSingleScope() {
            var classInfo = new ClassInfo(DotName.of("com.example.Good"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(),
                    List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.isEmpty());
        }
    }

    @Nested
    @DisplayName("@Produces + @Disposes on same method")
    class ProducesDisposesMethod {

        @Test
        @DisplayName("should reject method that is both producer and disposer")
        void rejectsProducerDisposer() {
            var method = new MethodInfo("produce", new TypeInfo.ClassType(DotName.of("java.lang.String")),
                    List.of(new ParameterInfo("resource", new TypeInfo.ClassType(DotName.of("java.lang.Object")),
                            List.of(ann(DISPOSES)))),
                    List.of(), 0x0001, List.of(ann(PRODUCES)));

            var classInfo = new ClassInfo(DotName.of("com.example.Bad"), DotName.of("java.lang.Object"),
                    List.of(), 0x0001, List.of(), List.of(method), List.of(ann(APP_SCOPED)), ClassKind.CLASS);

            var errors = ClassValidator.validate(buildIndex(classInfo));
            assertTrue(errors.stream().anyMatch(e -> e.contains("producer") && e.contains("disposer")));
        }
    }

    @Test
    @DisplayName("should accept a valid CDI bean with no issues")
    void acceptsValidBean() {
        var ctor = new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of());
        var observer = new MethodInfo("onEvent", new TypeInfo.VoidType(),
                List.of(new ParameterInfo("event", new TypeInfo.ClassType(DotName.of("java.lang.Object")),
                        List.of(ann(OBSERVES)))),
                List.of(), 0x0001, List.of());
        var producer = new MethodInfo("produce", new TypeInfo.ClassType(DotName.of("java.lang.String")),
                List.of(), List.of(), 0x0001, List.of(ann(PRODUCES)));

        var classInfo = new ClassInfo(DotName.of("com.example.Good"), DotName.of("java.lang.Object"),
                List.of(), 0x0001, List.of(), List.of(ctor, observer, producer),
                List.of(ann(APP_SCOPED)), ClassKind.CLASS);

        var errors = ClassValidator.validate(buildIndex(classInfo));
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }
}
