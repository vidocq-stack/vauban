package fr.vidocq.vauban.core.bean.model;

import fr.vidocq.vauban.indexer.model.DotName;

/**
 * Describes a CDI scope.
 * Normal scopes require a proxy (ApplicationScoped, RequestScoped, etc.).
 * Pseudo-scopes don't (Dependent, Singleton).
 */
public record ScopeInfo(DotName annotationName, boolean isNormal) {

    public static final ScopeInfo DEPENDENT = new ScopeInfo(
            DotName.of("jakarta.enterprise.context.Dependent"), false);

    public static final ScopeInfo APPLICATION = new ScopeInfo(
            DotName.of("jakarta.enterprise.context.ApplicationScoped"), true);

    public static final ScopeInfo REQUEST = new ScopeInfo(
            DotName.of("jakarta.enterprise.context.RequestScoped"), true);

    public static final ScopeInfo SINGLETON = new ScopeInfo(
            DotName.of("jakarta.inject.Singleton"), false);
}
