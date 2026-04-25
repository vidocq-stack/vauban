package fr.vidocq.vauban.core.extensions.testfixtures;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation trigger top-level pour les tests de rejeu BCE du bug #7
 * (cote runtime / vauban-core).
 *
 * <p>Extraite en classe top-level pour que les tests puissent referencer
 * son {@link java.lang.constant.ClassDesc} stable (pas de descripteur
 * {@code Outer$Inner}) et que les classes synthetisees via Class-File API
 * portent une annotation visible depuis n'importe quel ClassLoader.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PathLike {
    String value() default "";
}
