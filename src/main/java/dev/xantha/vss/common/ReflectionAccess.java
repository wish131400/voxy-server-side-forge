package dev.xantha.vss.common;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Optional compatibility APIs are stable for the lifetime of their defining class. */
public final class ReflectionAccess {
    private record Arity(String name, int count) { }
    private record Signature(String name, List<Class<?>> parameters) { }
    private static final class Members {
        final ConcurrentHashMap<String, Field> fields = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Arity, Method> byArity = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Signature, Method> bySignature = new ConcurrentHashMap<>();
    }
    private static final ClassValue<Members> MEMBERS = new ClassValue<>() {
        @Override protected Members computeValue(Class<?> type) { return new Members(); }
    };

    private ReflectionAccess() { }

    public static Object field(Object owner, String name) throws ReflectiveOperationException {
        Class<?> ownerClass = owner.getClass();
        Members members = MEMBERS.get(ownerClass);
        Field cached = members.fields.get(name);
        if (cached != null) return cached.get(owner);
        for (Class<?> type = ownerClass; type != null; type = type.getSuperclass()) {
            try {
                Field resolved = type.getDeclaredField(name);
                resolved.setAccessible(true);
                Field previous = members.fields.putIfAbsent(name, resolved);
                return (previous == null ? resolved : previous).get(owner);
            } catch (NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(name);
    }

    public static Method method(Class<?> type, String name, int argumentCount) throws ReflectiveOperationException {
        Members members = MEMBERS.get(type);
        Arity key = new Arity(name, argumentCount);
        Method cached = members.byArity.get(key);
        if (cached != null) return cached;
        for (Method resolved : type.getMethods()) {
            if (resolved.getName().equals(name) && resolved.getParameterCount() == argumentCount) {
                resolved.setAccessible(true);
                Method previous = members.byArity.putIfAbsent(key, resolved);
                return previous == null ? resolved : previous;
            }
        }
        throw new NoSuchMethodException(name);
    }

    public static Method method(Class<?> type, String name, Class<?>... parameterTypes) throws ReflectiveOperationException {
        Members members = MEMBERS.get(type);
        Signature key = new Signature(name, List.of(parameterTypes));
        Method cached = members.bySignature.get(key);
        if (cached != null) return cached;
        Method resolved = type.getMethod(name, parameterTypes);
        Method previous = members.bySignature.putIfAbsent(key, resolved);
        return previous == null ? resolved : previous;
    }

    public static Object call(Object owner, String name, Object... arguments) throws ReflectiveOperationException {
        return method(owner.getClass(), name, arguments.length).invoke(owner, arguments);
    }
}
