package fr.vidocq.vauban.indexer.model;

import java.util.List;

public sealed interface AnnotationValue {

    record StringVal(String value) implements AnnotationValue {}

    record BooleanVal(boolean value) implements AnnotationValue {}

    record ByteVal(byte value) implements AnnotationValue {}

    record CharVal(char value) implements AnnotationValue {}

    record ShortVal(short value) implements AnnotationValue {}

    record IntVal(int value) implements AnnotationValue {}

    record LongVal(long value) implements AnnotationValue {}

    record FloatVal(float value) implements AnnotationValue {}

    record DoubleVal(double value) implements AnnotationValue {}

    record ClassVal(DotName className) implements AnnotationValue {}

    record EnumVal(DotName enumType, String constantName) implements AnnotationValue {}

    record AnnotationVal(AnnotationInfo annotation) implements AnnotationValue {}

    record ArrayVal(List<AnnotationValue> values) implements AnnotationValue {
        public ArrayVal {
            values = List.copyOf(values);
        }
    }
}
