package io.vidocq.vauban.indexer.codegen;

/**
 * Describes an {@code @Inject} instance field (non-private, non-static) that the generated
 * {@code _VaubanComponents} provider can assign in-module without reflection.
 *
 * <p>Only fields in the same package as the generated provider are eligible: a {@code putfield}
 * to a package-private field only compiles (or executes without {@code opens}) from the same
 * package.
 *
 * @param declaringClassFqn fully-qualified name of the bean class declaring the field
 * @param fieldName         simple name of the field
 * @param fieldTypeErasure  erased, nameable type of the field (e.g. {@code "app.Repo"},
 *                          {@code "java.lang.String[]"})
 */
public record FieldInject(String declaringClassFqn, String fieldName, String fieldTypeErasure) {}
