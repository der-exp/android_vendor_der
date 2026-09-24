// Заглушка скрытого API платформы — ТОЛЬКО для компиляции оболочки splify2 вне дерева
// (tools/app-check/build.sh). В APK не попадает: на устройстве класс даёт framework.jar, а в
// дереве Soong компилирует приложение против полного API платформы (platform_apis: true).
//
// Сигнатуры — как у android.os.SystemProperties в frameworks/base (android16-qpr2): только те
// методы, которыми пользуется оболочка. Тела бросают исключение, чтобы случайное попадание
// заглушки в APK сразу было видно.
package android.os;

public class SystemProperties {
    private SystemProperties() {}

    public static String get(String key) {
        throw new RuntimeException("stub");
    }

    public static String get(String key, String def) {
        throw new RuntimeException("stub");
    }

    public static boolean getBoolean(String key, boolean def) {
        throw new RuntimeException("stub");
    }

    public static void set(String key, String val) {
        throw new RuntimeException("stub");
    }
}
