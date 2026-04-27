package io.vidocq.vauban.processor.apt.testfixtures;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation trigger top-level pour les tests d'APT du bug #7.
 *
 * <p>Extraite en classe top-level (et non en {@code @interface} nested
 * d'un test) pour eviter le defaut de visibilite "is defined in an
 * inaccessible class or interface" qui frappe les compilations
 * in-process via {@link javax.tools.JavaCompiler} : les classes
 * synthetisees in-memory ne peuvent pas resoudre une reference
 * vers une nested-class portee par la classe de test elle-meme,
 * meme avec un FQN explicite.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PathLike {
    String value() default "";
}
