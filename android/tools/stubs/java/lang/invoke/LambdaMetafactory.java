package java.lang.invoke;

/**
 * Compile-time stand-in for the one JDK class android.jar leaves out. javac needs
 * it to compile lambdas; d8 then desugars every lambda, so this is never packaged.
 */
public final class LambdaMetafactory {
    private LambdaMetafactory() {
    }

    public static CallSite metafactory(MethodHandles.Lookup caller, String name, MethodType type,
                                       MethodType samType, MethodHandle impl, MethodType instantiatedType) {
        throw new UnsupportedOperationException();
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller, String name, MethodType type, Object... args) {
        throw new UnsupportedOperationException();
    }
}
