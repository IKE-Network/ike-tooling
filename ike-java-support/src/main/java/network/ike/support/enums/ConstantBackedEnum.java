package network.ike.support.enums;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Pairs each constant of an enum with a matched
 * {@code public static final String} mirror field and verifies the
 * one-to-one correspondence at class-load time.
 *
 * <p>Annotation element values in Java must be constant expressions
 * (JLS &sect;15.28). {@code MyEnum.FOO.toString()} is not — method
 * calls never are; {@code MyEnum.FOO} itself is not, even when the
 * annotation element is {@code String}-typed. The only constant
 * handle on an enum-shaped name is a sibling
 * {@code public static final String NAME_FOO = "foo";} on the enum
 * class. This interface formalises that pattern: each constant passes
 * its mirror to the constructor by <em>qualified</em> name
 * ({@code Goal.NAME_FOO}) — a <em>simple-name</em> forward reference to
 * a field declared later is illegal under JLS &sect;8.3.3, but a
 * qualified reference is exempt from that restriction. The interface
 * contributes the {@link #literalName()} contract, and {@link #verify}
 * guarantees the constants and their {@code NAME_*} mirrors stay in
 * lockstep.
 *
 * <p>Usage — the consuming enum runs {@link #verify} from its static
 * initializer, so a structural mismatch makes the class permanently
 * unloadable rather than silently miscompiling whatever the mirror
 * feeds (e.g. a {@code plugin.xml} goal name):
 *
 * <pre>{@code
 * public enum Goal implements ConstantBackedEnum {
 *     CREATE(Goal.NAME_CREATE),
 *     ALIGN (Goal.NAME_ALIGN);
 *
 *     public static final String NAME_CREATE = "create";
 *     public static final String NAME_ALIGN  = "align";
 *
 *     public final String mojoName;
 *     Goal(String n) { this.mojoName = n; }
 *
 *     @Override public String literalName() { return mojoName; }
 *
 *     static { ConstantBackedEnum.verify(Goal.class); }
 * }
 * }</pre>
 *
 * Annotation site: {@code @Mojo(name = Goal.NAME_CREATE)}.
 */
public interface ConstantBackedEnum {

    /**
     * The String literal carried by this constant.
     *
     * @return the literal, equal to the corresponding {@code NAME_*}
     *         mirror constant on the implementing enum
     */
    String literalName();

    /**
     * Field-name prefix for the mirror constants.
     *
     * @return the default prefix, {@code "NAME_"}
     */
    static String defaultPrefix() { return "NAME_"; }

    /**
     * Verify mirror correspondence with the {@link #defaultPrefix()}
     * prefix and no value-shape check.
     *
     * @param enumClass the enum class to verify
     * @param <E>       the enum type
     */
    static <E extends Enum<E> & ConstantBackedEnum> void verify(Class<E> enumClass) {
        verify(enumClass, defaultPrefix(), null);
    }

    /**
     * Verify that every constant of {@code enumClass} has a matching
     * {@code public static final String} mirror field named
     * {@code prefix + constantName} whose value equals the constant's
     * {@link #literalName()}, that no mirror field is orphaned, and —
     * when {@code valueShape} is supplied — that each value satisfies
     * the shape predicate.
     *
     * @param enumClass  the enum class to verify
     * @param prefix     the mirror-field name prefix
     * @param valueShape optional per-constant value-shape check, or
     *                   {@code null} to skip it
     * @param <E>        the enum type
     * @throws AssertionError on any mismatch — intentionally, so the
     *                        violation surfaces as
     *                        {@code ExceptionInInitializerError} and
     *                        renders the class unusable
     */
    static <E extends Enum<E> & ConstantBackedEnum> void verify(
            Class<E> enumClass,
            String prefix,
            BiPredicate<E, String> valueShape) {

        Map<String, String> fields = new LinkedHashMap<>();
        for (Field f : enumClass.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isFinal(m)
                && f.getType() == String.class
                && f.getName().startsWith(prefix)) {
                try { fields.put(f.getName(), (String) f.get(null)); }
                catch (IllegalAccessException e) { throw new AssertionError(e); }
            }
        }

        for (E e : enumClass.getEnumConstants()) {
            String fname = prefix + e.name();
            String fval  = fields.remove(fname);
            if (fval == null)
                throw new AssertionError("Missing " + enumClass.getSimpleName() + "." + fname);
            if (!fval.equals(e.literalName()))
                throw new AssertionError(enumClass.getSimpleName() + "." + e.name()
                    + ": literalName=\"" + e.literalName() + "\" but "
                    + fname + "=\"" + fval + "\"");
            if (valueShape != null && !valueShape.test(e, fval))
                throw new AssertionError(enumClass.getSimpleName() + "." + e.name()
                    + ": value \"" + fval + "\" fails shape check");
        }

        if (!fields.isEmpty())
            throw new AssertionError("Orphan " + prefix + "* constants in "
                + enumClass.getSimpleName() + ": " + fields.keySet());
    }

    /**
     * Build an immutable {@code literalName -> constant} lookup index,
     * rejecting duplicate literals.
     *
     * @param enumClass the enum class to index
     * @param <E>       the enum type
     * @return an immutable map keyed by {@link #literalName()}
     * @throws AssertionError if two constants share a literal name
     */
    static <E extends Enum<E> & ConstantBackedEnum> Map<String, E> index(Class<E> enumClass) {
        E[] consts = enumClass.getEnumConstants();
        Map<String, E> map = new LinkedHashMap<>(consts.length * 2);
        for (E e : consts) {
            E prior = map.put(e.literalName(), e);
            if (prior != null)
                throw new AssertionError("Duplicate literalName \"" + e.literalName()
                    + "\" on " + prior + " and " + e);
        }
        return Map.copyOf(map);
    }
}
