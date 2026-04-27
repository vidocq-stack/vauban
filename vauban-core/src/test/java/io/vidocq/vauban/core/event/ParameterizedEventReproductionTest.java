package io.vidocq.vauban.core.event;

import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ParameterizedEventReproductionTest {

    @Test
    void testListStringMatching() {
        var builder = new IndexBuilder();
        var index = builder.build();
        var assignability = new AssignabilityRules(index);
        
        // Mock observer: void observeStringList(@Observes List<String> list)
        var stringListType = new TypeInfo.ParameterizedType(
                DotName.of("java.util.List"),
                List.of(new TypeInfo.ClassType(DotName.of("java.lang.String")))
        );
        
        var observer = new ObserverDescriptor(
                DotName.of("com.example.MyObserver"),
                "observeStringList",
                stringListType,
                List.of(),
                false,
                0
        );
        
        // Pass null for container, but EventDispatcher constructor currently does requireNonNull
        // Let's modify EventDispatcher temporarily to allow null container for this test or just pass a dummy if it doesn't use it for findMatchingObservers
        // Actually, let's just use the AssignabilityRules directly to see if the bug is there.
        
        var eventTypeInfo = io.vidocq.vauban.core.types.TypeInfoUtils.fromReflectType(createListStringType());
        boolean assignable = assignability.isAssignable(eventTypeInfo, stringListType);
        
        System.out.println("TEST DEBUG 1: eventTypeInfo=" + eventTypeInfo);
        System.out.println("TEST DEBUG 1: observerTypeInfo=" + stringListType);
        System.out.println("TEST DEBUG 1: assignable=" + assignable);
        
        assertTrue(assignable, "List<String> should be assignable to List<String>");

        // Test case 2: fire(ArrayList<String>, List.class)
        // This is what happens when calling event.select(List.class).fire(new ArrayList<String>())
        Type selectedRawList = List.class;
        // In EventDispatcher.fire, we do: resolveEventType(event.getClass(), selectedType)
        // If event is ArrayList<String>, event.getClass() is ArrayList.class (RAW)
        // resolveEventType(ArrayList.class, List.class)
        
        // Wait, ArrayList.class is RAW at runtime! event.getClass() loses generics!
        // That's why we need to trust selectedType if provided.
        
        System.out.println("TEST DEBUG 2: ArrayList.class.getTypeParameters().length=" + ArrayList.class.getTypeParameters().length);
    }

    private void assertTrue(boolean val, String msg) {
        if (!val) throw new AssertionError(msg);
    }

    private Type createListStringType() {
        return new ParameterizedType() {
            @Override public Type[] getActualTypeArguments() { return new Type[]{String.class}; }
            @Override public Type getRawType() { return List.class; }
            @Override public Type getOwnerType() { return null; }
            @Override public String toString() { return "java.util.List<java.lang.String>"; }
        };
    }
}
