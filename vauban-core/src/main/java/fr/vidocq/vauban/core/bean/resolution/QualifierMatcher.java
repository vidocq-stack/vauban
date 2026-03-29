package fr.vidocq.vauban.core.bean.resolution;

import fr.vidocq.vauban.core.bean.model.QualifierInstance;

import java.util.Set;

/**
 * Matches qualifier instances following CDI rules.
 */
public final class QualifierMatcher {

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
        // All members must match
        if (a.members().size() != b.members().size()) return false;
        for (var entry : a.members().entrySet()) {
            var otherVal = b.members().get(entry.getKey());
            if (otherVal == null || !entry.getValue().equals(otherVal)) return false;
        }
        return true;
    }
}
