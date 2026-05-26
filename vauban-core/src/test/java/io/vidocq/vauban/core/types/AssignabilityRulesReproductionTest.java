package io.vidocq.vauban.core.types;

import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignabilityRulesReproductionTest {

    private AssignabilityRules rules;

    @BeforeEach
    void setUp() {
        var builder = new IndexBuilder();
        builder.add(AssignabilityRulesTest.makeClass("java.lang.Object", null));
        builder.add(AssignabilityRulesTest.makeClass("java.lang.Number", "java.lang.Object"));
        builder.add(AssignabilityRulesTest.makeClass("java.lang.Integer", "java.lang.Number"));
        rules = new AssignabilityRules(builder.build());
    }

    @Test
    @DisplayName("List<? extends Integer> must be assignable to List<? extends Number>")
    void wildcardToWildcardAssignability() {
        var listExtendsInteger = new ParameterizedType(
                DotName.of("java.util.List"),
                List.of(new WildcardType(new ClassType(DotName.of("java.lang.Integer")), null)));
        
        var listExtendsNumber = new ParameterizedType(
                DotName.of("java.util.List"),
                List.of(new WildcardType(new ClassType(DotName.of("java.lang.Number")), null)));

        // BUG: isTypeArgumentAssignable does not handle Wildcard vs Wildcard
        assertTrue(rules.isAssignable(listExtendsInteger, listExtendsNumber),
                "List<? extends Integer> should be assignable to List<? extends Number>");
    }

    @Test
    @DisplayName("List<T extends Integer> must be assignable to List<? extends Number>")
    void typeVariableToWildcardAssignability() {
        var typeVarT = new TypeVariable("T", List.of(new ClassType(DotName.of("java.lang.Integer"))));
        var listT = new ParameterizedType(
                DotName.of("java.util.List"),
                List.of(typeVarT));

        var listExtendsNumber = new ParameterizedType(
                DotName.of("java.util.List"),
                List.of(new WildcardType(new ClassType(DotName.of("java.lang.Number")), null)));

        // BUG: isTypeArgumentAssignable does not handle TypeVariable vs Wildcard
        assertTrue(rules.isAssignable(listT, listExtendsNumber),
                "List<T extends Integer> should be assignable to List<? extends Number>");
    }
}
