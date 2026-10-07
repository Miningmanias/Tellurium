// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.loader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Minecraft's classes, fields and methods by the names this mod's source uses for them.
 *
 * <p>Parts of the mod read the game's world generator by reflection: which kind of density function a node
 * is, what a private field holds.  The names in the source are the ones the game has on NeoForge and in any
 * development environment.  A released Fabric game runs with other names for the same things; there this
 * class translates through {@link Loader}, which holds a table for exactly the names the mod uses.  Everywhere
 * else the translation is the identity.</p>
 *
 * <p>This class is the same on every loader (it is the one class of this package that is).</p>
 */
public final class Names {
    private Names() {}

    /** The class's simple name as the source knows it; for a class the table does not hold, its runtime simple name. */
    public static String simpleName(Class<?> type) {
        return Loader.simpleName(type);
    }

    /** {@link Class#getDeclaredField} by source name. */
    public static Field declaredField(Class<?> owner, String name) throws NoSuchFieldException {
        return owner.getDeclaredField(Loader.fieldName(owner, name));
    }

    /** The runtime name of a field declared by the class, given its source name. */
    public static String fieldName(Class<?> owner, String name) {
        return Loader.fieldName(owner, name);
    }

    /** {@link Class#getMethod} by source name: the method may be declared anywhere in the class's hierarchy. */
    public static Method publicMethod(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (String candidate : candidates(type, name)) {
            try {
                return type.getMethod(candidate, parameters);
            } catch (NoSuchMethodException next) {
                // try the next name
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    /** {@link Class#getDeclaredMethod} by source name. */
    public static Method declaredMethod(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (String candidate : candidates(owner, name)) {
            try {
                return owner.getDeclaredMethod(candidate, parameters);
            } catch (NoSuchMethodException next) {
                // try the next name
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    /** Whether the method, declared somewhere in the given type's hierarchy, is the one the source calls by this name. */
    public static boolean isNamed(Method method, Class<?> type, String name) {
        return candidates(type, name).contains(method.getName());
    }

    /** Whether the object has a public method without parameters that the source calls by this name. */
    public static boolean hasPublicNoArgMethod(Object target, String name) {
        Set<String> names = candidates(target.getClass(), name);
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && names.contains(method.getName())) return true;
        }
        return false;
    }

    /** The source name itself, then whatever runtime names the type and everything it extends or implements give it. */
    private static Set<String> candidates(Class<?> type, String name) {
        Set<String> names = new LinkedHashSet<>();
        names.add(name);
        if (!Loader.translatesNames()) return names;
        ArrayDeque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> seen = new java.util.HashSet<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> current = pending.poll();
            if (!seen.add(current)) continue;
            names.addAll(Loader.methodNames(current, name));
            if (current.getSuperclass() != null) pending.add(current.getSuperclass());
            for (Class<?> implemented : current.getInterfaces()) pending.add(implemented);
        }
        return names;
    }
}
