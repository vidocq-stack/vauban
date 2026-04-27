package io.vidocq.vauban.core.bean.resolution;

import io.vidocq.vauban.core.bean.model.QualifierInstance;

import java.util.Set;

/**
 * Matches qualifier instances following CDI rules.
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class QualifierMatcher {

    // Volatile immutable map: assigned once at startup, read-only after — thread-safe by design
    @SuppressWarnings("java:S3077")
    private static volatile java.util.Map<String, Set<String>> customNonbindingMembers = java.util.Map.of();

    public static void setCustomNonbindingMembers(java.util.Map<String, Set<String>> nonbindingMembers) {
        customNonbindingMembers = nonbindingMembers != null ? nonbindingMembers : java.util.Map.of();
    }

    public static Set<String> getCustomNonbindingMembers(String qualifierName) {
        return customNonbindingMembers.get(qualifierName);
    }

    private QualifierMatcher() {}

    /**
     * Checks if a bean's qualifiers match the required qualifiers at an injection point.
     * CDI rule: every required qualifier must be present on the bean.
     * {@code @Any} matches everything. {@code @Default} matches when the bean has {@code @Default}.
     */
    public static boolean matches(Set<QualifierInstance> beanQualifiers, Set<QualifierInstance> requiredQualifiers) {
        for (var required : requiredQualifiers) {
            if (required.isAny()) continue; // @Any matches everything
            if (!containsQualifier(beanQualifiers, required)) return false;
        }
        return true;
    }

    /**
     * Checks if the bean qualifier set contains a qualifier matching the required one.
     * Two qualifiers match if they have the same annotation type AND all member values are equal.
     */
    static boolean containsQualifier(Set<QualifierInstance> beanQualifiers, QualifierInstance required) {
        return beanQualifiers.stream().anyMatch(bq -> qualifierEquals(bq, required));
    }

    static boolean qualifierEquals(QualifierInstance a, QualifierInstance b) {
        if (!a.annotationName().equals(b.annotationName())) return false;
        // Compare only non-@Nonbinding members
        // First, determine which members are @Nonbinding via reflection
        java.util.Set<String> nonBindingMembers;
        try {
            var annClass = Class.forName(a.annotationName().value());
            nonBindingMembers = new java.util.HashSet<>();
            for (var method : annClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.enterprise.util.Nonbinding.class)) {
                    nonBindingMembers.add(method.getName());
                }
            }
        } catch (ClassNotFoundException e) {
            nonBindingMembers = java.util.Set.of();
        }
        // Add custom nonbinding members from @Discovery phase
        var customNb = customNonbindingMembers.get(a.annotationName().value());
        if (customNb != null) {
            nonBindingMembers = new java.util.HashSet<>(nonBindingMembers);
            nonBindingMembers.addAll(customNb);
        }

        // Compare binding members only
        for (var entry : a.members().entrySet()) {
            if (nonBindingMembers.contains(entry.getKey())) continue;
            var otherVal = b.members().get(entry.getKey());
            if (otherVal == null || !entry.getValue().equals(otherVal)) return false;
        }
        for (var entry : b.members().entrySet()) {
            if (nonBindingMembers.contains(entry.getKey())) continue;
            if (!a.members().containsKey(entry.getKey())) return false;
        }
        return true;
    }
}
