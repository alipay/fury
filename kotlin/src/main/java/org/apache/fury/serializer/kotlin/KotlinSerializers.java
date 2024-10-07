package org.apache.fury.serializer.kotlin;

import org.apache.fury.Fury;
import org.apache.fury.resolver.ClassResolver;
import org.apache.fury.serializer.Serializer;
import org.apache.fury.serializer.SerializerFactory;
import org.apache.fury.serializer.collection.CollectionSerializers;
import org.apache.fury.serializer.collection.MapSerializers;


@SuppressWarnings({"rawtypes", "unchecked"})
public class KotlinSerializers {
    public static void registerSerializers(Fury fury) {
        ClassResolver resolver = setSerializerFactory(fury);

        // EmptyList
        Class emptyListClass = KotlinToJavaClass.INSTANCE.getEmptyListClass();
        resolver.register(emptyListClass);
        resolver.registerSerializer(emptyListClass, new CollectionSerializers.EmptyListSerializer(fury, emptyListClass));

        // EmptySet
        Class emptySetClass = KotlinToJavaClass.INSTANCE.getEmptySetClass();
        resolver.register(emptySetClass);
        resolver.registerSerializer(emptySetClass, new CollectionSerializers.EmptySetSerializer(fury, emptySetClass));

        // EmptyMap
        Class emptyMapClass = KotlinToJavaClass.INSTANCE.getEmptyMapClass();
        resolver.register(emptyMapClass);
        resolver.registerSerializer(emptyMapClass, new MapSerializers.EmptyMapSerializer(fury, emptyMapClass));

        // (Mutable)MapWithDefault
        Class mapWithDefaultClass = KotlinToJavaClass.INSTANCE.getMapWithDefaultClass();
        resolver.register(mapWithDefaultClass);
        resolver.registerSerializer(mapWithDefaultClass, new MapWithDefaultSerializer(fury, mapWithDefaultClass));
        Class mutableMapWithDefaultClass = KotlinToJavaClass.INSTANCE.getMutableMapWitDefaultClass();
        resolver.register(mutableMapWithDefaultClass);
        resolver.registerSerializer(mutableMapWithDefaultClass, new MapWithDefaultSerializer(fury, mutableMapWithDefaultClass));

        // Non-Java collection implementation in kotlin stdlib.
        Class arrayDequeClass = KotlinToJavaClass.INSTANCE.getArrayDequeClass();
        resolver.register(arrayDequeClass);
        resolver.registerSerializer(arrayDequeClass, new KotlinArrayDequeSerializer(fury, arrayDequeClass));
    }

    private static ClassResolver setSerializerFactory(Fury fury) {
        ClassResolver resolver = fury.getClassResolver();
        KotlinDispatcher dispatcher = new KotlinDispatcher();
        SerializerFactory factory = resolver.getSerializerFactory();
        if (factory != null) {
            SerializerFactory newFactory = (f, cls) -> {
                Serializer<?> serializer = factory.createSerializer(f, cls);
                if (serializer == null) {
                    serializer = dispatcher.createSerializer(f, cls);
                }
                return serializer;
            };
            resolver.setSerializerFactory(newFactory);
        } else {
            resolver.setSerializerFactory(dispatcher);
        }
        return resolver;
    }
}
