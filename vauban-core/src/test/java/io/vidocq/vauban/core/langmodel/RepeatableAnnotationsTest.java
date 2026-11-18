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
package io.vidocq.vauban.core.langmodel;

import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("repeatableAnnotation unwraps the container of a repeatable annotation (BUG-20261117-56)")
class RepeatableAnnotationsTest {

    @Repeatable(Reps.class)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Rep {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Reps {
        Rep[] value();
    }

    /** An annotation whose {@code value} is an array of {@link Rep}, but which {@link Rep} does not name. */
    @Retention(RetentionPolicy.RUNTIME)
    @interface Holder {
        Rep[] value();
    }

    /** An annotation type that is not repeatable. */
    @Retention(RetentionPolicy.RUNTIME)
    @interface Single {
        String value();
    }

    /** An annotation whose {@code value} is an array of {@link Single}, not repeatable: no container. */
    @Retention(RetentionPolicy.RUNTIME)
    @interface Singles {
        Single[] value();
    }

    @Rep("a")
    @Rep("b")
    static class Repeated {
    }

    @Singles(@Single("held"))
    static class HeldByNoContainer {
    }

    @Rep("single")
    @Holder(@Rep("held"))
    static class HeldElsewhere {
    }

    private static List<String> values(Collection<AnnotationInfo> annotations) {
        return annotations.stream().map(annotation -> annotation.value().asString()).toList();
    }

    private static VaubanClassInfo classInfo(Class<?> annotated, Class<?>... described) throws IOException {
        var classes = new ArrayList<Class<?>>(List.of(described));
        classes.add(annotated);
        var lookup = LangModelTest.buildLookup(classes.toArray(Class<?>[]::new));
        return new VaubanClassInfo(LangModelTest.scanClass(annotated), lookup);
    }

    @Test
    @DisplayName("the annotations a container holds are those of the repeatable type")
    void theAnnotationsAContainerHoldsAreThoseOfTheRepeatableType() throws IOException {
        var repeated = classInfo(Repeated.class, Rep.class, Reps.class);

        assertEquals(List.of("a", "b"), values(repeated.repeatableAnnotation(Rep.class)));
    }

    @Test
    @DisplayName("an annotation the repeatable type does not name as its container holds none of them")
    void anAnnotationTheRepeatableTypeDoesNotNameHoldsNone() throws IOException {
        var held = classInfo(HeldElsewhere.class, Rep.class, Reps.class, Holder.class);

        assertEquals(List.of("single"), values(held.repeatableAnnotation(Rep.class)));
    }

    @Test
    @DisplayName("an annotation type that is not repeatable has no container")
    void anAnnotationTypeThatIsNotRepeatableHasNoContainer() throws IOException {
        var held = classInfo(HeldByNoContainer.class, Single.class, Singles.class);

        assertEquals(List.of(), values(held.repeatableAnnotation(Single.class)));
    }

    @Test
    @DisplayName("a repeatable type nothing describes is taken at its word")
    void aRepeatableTypeNothingDescribesIsTakenAtItsWord() throws IOException {
        var repeated = classInfo(Repeated.class);

        assertEquals(List.of("a", "b"), values(repeated.repeatableAnnotation(Rep.class)));
    }
}
