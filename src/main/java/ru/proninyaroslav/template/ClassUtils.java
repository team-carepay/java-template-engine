package ru.proninyaroslav.template;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ClassUtils {
    public static Field findField(Class<?> clazz, String fieldName) {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass == null) {
                return null;
            } else {
                return findField(superClass, fieldName);
            }
        }
    }

    public static List<Method> findMethods(Class<?> clazz, String methodName) {
        List<Method> methods = new ArrayList<>();
        Class<?> cl = clazz;
        do {
            for (Method method : cl.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    methods.add(method);
                }
            }
            cl = cl.getSuperclass();
        } while (cl != null);
        return methods;
    }
}
