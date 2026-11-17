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
package io.vidocq.vauban.indexer.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ClassInfo type parameters (vauban#207) and generic supertypes (BUG-20261117-22)")
class ClassInfoTest {

    private static final DotName NAME = DotName.of("app.Outer$Box");
    private static final DotName OBJECT = DotName.of("java.lang.Object");
    private static final List<TypeInfo.TypeVariable> TYPE_PARAMETERS = List.of(
            new TypeInfo.TypeVariable("T", List.of(new TypeInfo.ClassType(DotName.of("java.lang.Number")))));
    private static final DotName BASE = DotName.of("app.Base");
    private static final DotName SUPPLIER = DotName.of("java.util.function.Supplier");
    private static final TypeInfo BASE_OF_T = new TypeInfo.ParameterizedType(BASE, List.copyOf(TYPE_PARAMETERS));
    private static final TypeInfo SUPPLIER_OF_STRING = new TypeInfo.ParameterizedType(SUPPLIER,
            List.of(new TypeInfo.ClassType(DotName.of("java.lang.String"))));

    @Test
    @DisplayName("a class info built without type parameters declares none")
    void builtWithoutTypeParameters() {
        var info = new ClassInfo(NAME, OBJECT, List.of(), 0, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "Box");
        assertEquals("Box", info.simpleName());
        assertEquals(List.of(), List.copyOf(info.typeParameters()));
    }

    @Test
    @DisplayName("another set of annotations keeps the type parameters")
    void withAnnotationsKeepsTypeParameters() {
        var info = new ClassInfo(NAME, OBJECT, List.of(), 0, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "Box", TYPE_PARAMETERS);
        var annotated = info.withAnnotations(List.of(new AnnotationInfo(DotName.of("app.Marker"), java.util.Map.of())));
        assertEquals(TYPE_PARAMETERS, annotated.typeParameters());
        assertEquals(1, annotated.annotations().size());
    }

    @Test
    @DisplayName("a class info built without generic supertypes names its supertypes raw (BUG-20261117-22)")
    void builtWithoutGenericSupertypes() {
        var info = new ClassInfo(NAME, BASE, List.of(SUPPLIER), 0, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS);
        assertEquals(new TypeInfo.ClassType(BASE), info.genericSuperclass());
        assertEquals(List.of(new TypeInfo.ClassType(SUPPLIER)), info.genericInterfaces());
    }

    @Test
    @DisplayName("a class info without a superclass has no generic superclass")
    void withoutSuperclass() {
        var info = new ClassInfo(OBJECT, null, List.of(), 0, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "Object", List.of(), true, null, null);
        assertNull(info.genericSuperclass());
        assertEquals(List.of(), info.genericInterfaces());
    }

    /**
     * A generic superclass says how the class declares the superclass {@code superName} names: without one, it is
     * refused, as generic interfaces that do not line up with the interfaces are, rather than dropped.
     */
    @Test
    @DisplayName("a generic superclass without a superclass is refused")
    void aGenericSuperclassWithoutASuperclassIsRefused() {
        var base = new TypeInfo.ClassType(BASE);
        List<DotName> interfaces = List.of();
        List<FieldInfo> fields = List.of();
        List<MethodInfo> methods = List.of();
        List<AnnotationInfo> annotations = List.of();
        List<TypeInfo.TypeVariable> typeParameters = List.of();
        List<TypeInfo> genericInterfaces = List.of();
        var error = assertThrows(IllegalArgumentException.class, () -> new ClassInfo(OBJECT, null, interfaces, 0,
                fields, methods, annotations, ClassInfo.ClassKind.CLASS, "Object", typeParameters, true, base,
                genericInterfaces));
        assertEquals("java.lang.Object has no superclass, but a generic superclass", error.getMessage());
    }

    @Test
    @DisplayName("another set of annotations keeps the generic supertypes")
    void withAnnotationsKeepsGenericSupertypes() {
        var info = generic(List.of(SUPPLIER_OF_STRING));
        var annotated = info.withAnnotations(List.of(new AnnotationInfo(DotName.of("app.Marker"), java.util.Map.of())));
        assertEquals(BASE_OF_T, annotated.genericSuperclass());
        assertEquals(List.of(SUPPLIER_OF_STRING), annotated.genericInterfaces());
    }

    @Test
    @DisplayName("generic interfaces that do not line up with the interfaces are refused")
    void genericInterfacesLineUpWithTheInterfaces() {
        List<TypeInfo> none = List.of();
        var error = assertThrows(IllegalArgumentException.class, () -> generic(none));
        assertEquals("app.Outer$Box declares 1 interfaces, not 0", error.getMessage());
    }

    /** {@code Box<T> extends Base<T>}, which implements {@code Supplier}, with {@code genericInterfaces}. */
    private static ClassInfo generic(List<TypeInfo> genericInterfaces) {
        return new ClassInfo(NAME, BASE, List.of(SUPPLIER), 0, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "Box", TYPE_PARAMETERS, true, BASE_OF_T, genericInterfaces);
    }
}
